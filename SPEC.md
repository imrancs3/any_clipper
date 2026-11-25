# Project: "Clipper" — Personal AI Short-Clip Generator (like vizard.ai / ssemble.ai)

## 1. Goal
Build a personal-use web app that takes a long video (local upload or a YouTube link), transcribes it,
uses an LLM to find the most engaging moments, and exports vertical 9:16 short clips with a
face-tracked crop and animated word-by-word captions.

- Single user, runs on a LOW-END local PC (assume 4–8 GB RAM, weak CPU, no GPU).
- Volume: about 50–80 hours of source video per month.
- All heavy work (transcription, AI selection, rendering) is done through cloud APIs.
- The local machine only does light work: saving files, extracting audio, copying video segments
  without re-encoding (stream copy), and serving the UI.

## 2. Tech Stack (do NOT change without asking)
- Backend: Java 21, Spring Boot 3.x, Maven.
- Concurrency: virtual threads (spring.threads.virtual.enabled=true) and @Async for pipeline jobs.
- Database: H2 in file mode (./data/clipper-db) with Spring Data JPA.
- HTTP clients: Spring's RestClient (plain and explicit; no Spring AI, no heavy SDKs).
- Local media: FFmpeg CLI called through ProcessBuilder (one small helper class).
  yt-dlp CLI (optional) for YouTube links.
- Live progress: Server-Sent Events (SseEmitter).
- Transcription: Groq API, OpenAI-compatible endpoint
  POST https://api.groq.com/openai/v1/audio/transcriptions
  model "whisper-large-v3-turbo" (configurable), response_format=verbose_json,
  timestamp_granularities[]=word.
- Clip selection: Google Gemini API (a Flash-tier model, name configurable), JSON output mode
  (responseMimeType=application/json) with a defined response schema.
- Cloud render worker: ONE small Python app on Modal (modal.com) exposed as an HTTPS web endpoint.
  Uses MediaPipe face detection, OpenCV and FFmpeg. CPU only, no GPU.
- Frontend: React + Vite + TypeScript + Tailwind CSS + react-router-dom.
- Serving: Spring Boot serves the built frontend (frontend/dist) as static resources, so normal
  use is ONE java process. The Vite dev server is only for frontend development.

## 3a. Architecture & Pipeline
Each job moves through these statuses (stored in DB, pushed to the UI over SSE):
QUEUED -> DOWNLOADING -> EXTRACTING_AUDIO -> TRANSCRIBING -> SELECTING_CLIPS -> CUTTING -> RENDERING -> DONE | FAILED

Step 1 — Ingest
- Upload (multipart, streamed to disk; must handle files of several GB without loading into memory)
  OR a YouTube URL downloaded with yt-dlp (best quality up to 1080p)
  OR a public Google Drive file link, streamed directly to disk by the backend.
- Storage layout: ./data/jobs/{jobId}/source.mp4, /audio/, /segments/, /clips/, transcript.json

Step 2 — Audio extraction (local, light)
- ffmpeg: mono, 16 kHz, 64 kbps MP3.
- Split into chunks of about 10–15 min so each stays under Groq's upload size limit (make chunk length configurable).

Step 3 — Transcription (Groq)
- Send chunks one by one (simple retry with backoff on 429/5xx, max 3 attempts).
- Offset each chunk's word timestamps by the chunk's start time, then merge into one transcript:
  list of { word, start, end } plus segment/sentence text.
- Save transcript.json and also store it in the DB.

Step 4 — Clip selection (Gemini)
- Send the full transcript as time-stamped sentences (e.g. "[123.4] sentence text").
- Prompt the model to act as a short-form video editor: find self-contained, hook-driven moments
  with a strong opening line and a clear ending.
- Configurable: clip count (default 10), min/max duration (default 30–90 s).
- Required JSON response:
  { "clips": [ {
      "start": number, "end": number,
      "title": string,              // catchy, max 60 chars, curiosity/benefit driven, no clickbait lies
      "altTitles": [string, string],// 2 alternative title styles (e.g. question, bold statement)
      "hook": string,               // the opening line that grabs attention
      "description": string,        // 1–2 sentence post caption, max 200 chars
      "hashtags": [string],         // 5–8 hashtags: mix of broad + niche, lowercase, no spaces, with '#'
      "keywords": [string],         // 3–5 plain topic keywords for SEO/YouTube tags
      "score": 0-100,
      "reason": string
  } ] }
- Titles, descriptions and tags must reflect only what is said in the clip (transcript-based).
- Output language matches the video's spoken language (configurable override in settings).
- Validate in Java: trim title to 60 chars, normalise hashtags (lowercase, add '#', remove duplicates
  and spaces), cap counts (hashtags max 8, keywords max 5).
- Store hashtags/keywords/altTitles on the Clip entity as JSON text columns (no extra tables).
- Per-clip metadata prompt lives in resources/prompts/clip-metadata.txt.
- Validate in Java: clamp to video length, enforce duration limits, snap start/end to the nearest
  word boundaries, drop overlaps, sort by score.

Step 5 — Local cut (no re-encode)
- Stream copy can only cut at keyframes, so first ffprobe the source (keyframes only, short window)
  for the last keyframe at or before (start - 3s pre-roll). Store it on the Clip as segmentStartSeconds.
- For each clip: ffmpeg -ss (segmentStartSeconds) -to (end + 1s) -c copy -> segments/{clipId}.mp4
  The segment then really starts at segmentStartSeconds. (Probing the segment file itself does not
  work: ffmpeg resets the copied segment's timestamps to 0.)
- The exact trim happens in the render worker (it re-encodes anyway).
  Pass trimStart = clip.start - segmentStartSeconds and duration.

Step 6 — Captions (generated in JAVA, not Python)
- CaptionService builds an .ass subtitle file per clip from the word timestamps:
  - PlayResX=1080, PlayResY=1920, groups of 3–5 words per line, current word highlighted
    (different colour / slight scale-up), positioned in the lower-middle third.
  - Times must be relative to the exact clip start (after the worker trims).
  - 3 style presets (e.g. "bold-yellow", "clean-white", "boxed"), selectable per clip.
  - Font: one bundled open-source font (e.g. Montserrat ExtraBold), same name as in the worker image.

Step 7 — Render (Modal Python worker)
- Java sends a multipart POST to MODAL_RENDER_URL with:
  video (segment mp4), captions (.ass), options JSON
  { trimStart, duration, width:1080, height:1920, faceTracking:true }.
- Header "Authorization: Bearer <RENDER_SHARED_SECRET>"; the worker rejects anything else.
- Worker steps:
  1. Sample frames (~5 fps) with MediaPipe face detection; take the largest/most central face.
  2. Compute the crop centre x for each sample, fill gaps, smooth (moving average + dead zone so
     the camera doesn't jitter), clamp to frame bounds. No face -> centre crop.
  3. Read frames with OpenCV, crop to 9:16, scale to 1080x1920, pipe raw frames into ffmpeg,
     map the original audio, burn the .ass captions with the ass filter (fontsdir = bundled fonts).
     Output H.264 + AAC MP4, faststart.
  4. Return the MP4 bytes in the response.
- Keep the worker to ONE file (render_worker.py, about 150–250 lines) plus the image definition,
  using Modal's current web-endpoint decorator. Timeout of about 10 min per call.
- Java saves the result to clips/{clipId}.mp4 and renders up to 3 clips in parallel (configurable).
- If request size limits on Modal become a problem, document it; do NOT add cloud storage
  unless asked.

Step 8 — UI
- Page 1 "Home": upload a file or paste a URL, settings (clip count, durations, caption preset),
  list of past jobs.
- Page 2 "Job": live progress bar/status via SSE, then a grid of clips (video player, title, score,
  reason, download button).
- Clip editor (modal/drawer on the Job page): edit caption words/text, change preset, adjust
  start/end by ±seconds, "Re-render" button (re-runs only Steps 5–7 for that clip).
- ClipCard shows the title, hashtags as chips, and a "Copy" button that copies
  title + description + hashtags as one ready-to-paste post text.
- ClipEditor lets the user edit title/description/tags, pick an alternative title,
  and click "Regenerate titles & tags".

## 3b. Scheduled Posting
Goal: schedule finished clips to YouTube Shorts, Instagram Reels and TikTok, using the clip's
title, description and hashtags (editable per platform before scheduling).

Accounts & OAuth
- "Connected Accounts" settings page: connect/disconnect YouTube (Google OAuth), Instagram
  (Meta OAuth, Business/Creator account) and TikTok (TikTok OAuth).
- Implement the OAuth authorization-code flow manually with RestClient (one callback endpoint
  per platform). Do NOT add Spring Security.
- Store access/refresh tokens in the DB, encrypted with AES-GCM using TOKEN_ENCRYPTION_KEY from the
  environment. Refresh tokens automatically before expiry.
- Client IDs/secrets only from environment variables:
  YOUTUBE_CLIENT_ID/SECRET, META_APP_ID/SECRET, TIKTOK_CLIENT_KEY/SECRET.
- All OAuth redirect URLs are built as {PUBLIC_BASE_URL}/api/accounts/{platform}/callback
  (PUBLIC_BASE_URL = an HTTPS tunnel such as an ngrok static domain).
- Use a random OAuth "state" parameter and verify it in the callback (CSRF protection).
- Google: request access_type=offline&prompt=consent so a refresh token is returned.
  Scopes: youtube.upload and youtube.
- Instagram: "Instagram API with Instagram Login" (no Facebook Page). Scopes:
  instagram_business_basic, instagram_business_content_publish. Exchange for a long-lived token
  and refresh it weekly via refresh_access_token (tokens expire after ~60 days).
- TikTok: scopes user.info.basic, video.publish. Use the FILE_UPLOAD method (not PULL_FROM_URL).
  Call the creator_info endpoint before every post and only offer the privacy options it returns.
- YouTube: if the API project is unaudited, uploads are forced to private and publishAt has no
  effect. Detect this, show a clear UI note, and fall back to assisted mode for YouTube.

Scheduling model
- New entity ScheduledPost: clipId, platform (YOUTUBE | INSTAGRAM | TIKTOK), scheduledAt
  (stored in UTC, shown in the configured timezone, default Europe/Berlin), title, description,
  hashtags, privacy, status (SCHEDULED -> UPLOADING -> PUBLISHED | FAILED | MISSED),
  platformPostId, postUrl, errorMessage, attemptCount.
- YouTube: upload immediately after scheduling (resumable upload) with privacyStatus=private and
  status.publishAt=scheduledAt, so YouTube publishes it even if the PC is off.
  Track daily quota use and warn when close to the limit.
- Instagram & TikTok: a @Scheduled poller (every 60 s) publishes due posts.
  - Instagram: upload clip to a public Cloudflare R2 bucket (S3-compatible API, via RestClient or
    the AWS SDK S3 module only), create a REELS media container with video_url, poll until
    FINISHED, then publish. Delete the R2 object after publishing.
  - TikTok: Content Posting API (direct post); if the app is unaudited, post as SELF_ONLY and
    show a clear note in the UI.
- Retries: up to 3 attempts with backoff on network/5xx errors; never retry on auth errors
  (mark FAILED with "reconnect account").
- On startup: posts that are overdue by up to N hours (configurable, default 2) are published;
  older ones become MISSED and wait for user action ("Post now" / "Reschedule").
- "Assisted mode" per platform (setting): instead of calling the API, show a notification at the
  scheduled time with Download + "Copy post text" buttons, and a "Mark as posted" button.

Code organisation (follows Section 6)
- One class per platform: YouTubePublisher, InstagramPublisher, TikTokPublisher (each handles
  its own OAuth, upload and publish; 150–350 lines is fine). NO common interface.
- PostSchedulerService: owns the poller, retries, startup catch-up, and calls the right publisher
  with a simple switch on platform.
- OAuthTokenService: token storage, encryption, refresh.

API additions
- GET  /api/accounts                     connected accounts + status
- GET  /api/accounts/{platform}/connect  start OAuth
- GET  /api/accounts/{platform}/callback OAuth callback
- DELETE /api/accounts/{platform}
- POST /api/clips/{id}/schedule          { platform, scheduledAt, title, description, hashtags, privacy }
- GET  /api/schedule?from=&to=           list scheduled posts
- PUT  /api/schedule/{id}                reschedule / edit text
- POST /api/schedule/{id}/publish-now
- DELETE /api/schedule/{id}              cancel (also deletes the private YouTube upload if not published)

UI additions
- ClipCard: "Schedule" button -> ScheduleDialog (platform checkboxes, date/time picker,
  per-platform editable title/description/hashtags, character limits shown).
- New SchedulePage: list grouped by day (calendar view optional), status badges, post links,
  retry / reschedule / cancel actions.
- SettingsPage: connected accounts, timezone, assisted mode toggles.

Tests
- Timezone conversion, due-post selection, catch-up/missed logic, retry rules, token encryption
  round-trip.

Milestone
- M7: Scheduled posting — YouTube first, then Instagram, then TikTok; verify each before the next.

## 4. REST API (keep it small)
- POST   /api/jobs                (multipart upload OR JSON { url }, plus settings)
- GET    /api/jobs                 list
- GET    /api/jobs/{id}            job + clips
- GET    /api/jobs/{id}/events     SSE progress
- PUT    /api/clips/{id}           update captions/style/start/end/title/description/hashtags/keywords
- POST   /api/clips/{id}/metadata  regenerate title/description/tags for ONE clip (only that clip's transcript)
- POST   /api/clips/{id}/render    re-render one clip
- GET    /api/clips/{id}/file      stream mp4 (supports Range requests for seeking)
- DELETE /api/jobs/{id}            delete job + files

## 5. Configuration
- All settings in application.yml, with secrets ONLY from environment variables:
  GROQ_API_KEY, GEMINI_API_KEY, MODAL_RENDER_URL, RENDER_SHARED_SECRET,
  PUBLIC_BASE_URL, APP_ACCESS_TOKEN, TOKEN_ENCRYPTION_KEY,
  YOUTUBE_CLIENT_ID, YOUTUBE_CLIENT_SECRET, META_APP_ID, META_APP_SECRET,
  TIKTOK_CLIENT_KEY, TIKTOK_CLIENT_SECRET,
  R2_ACCOUNT_ID, R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET, R2_PUBLIC_BASE_URL.
- Provide .env.example. Never hard-code keys. Never log keys.
- Use ONE @ConfigurationProperties record (ClipperProperties) for all app settings.
- On startup: check that ffmpeg (and yt-dlp if enabled) are on PATH and log a clear message if not.
- On startup: any job left in a running status -> mark FAILED with "interrupted by restart".
- Simple access protection: one small servlet filter requires header
  "X-Access-Token: <APP_ACCESS_TOKEN>" (or a cookie set via a minimal login page) on all /api/**
  requests, EXCEPT the OAuth callback endpoints. No Spring Security.

## 6. CODING STANDARDS — VERY IMPORTANT
I am the long-term maintainer and I know Java well. The code must be easy for ME to read,
understand and change later. Prefer a FEW well-organised, readable files over many tiny ones.

DO:
- Follow Google Java Style (2-space or 4-space indent, but be consistent; max ~120 chars per line).
- Organise by feature/pipeline stage: ONE service class per stage (AudioService,
  TranscriptionService, ClipSelectionService, CaptionService, RenderClient, PipelineService that
  runs the steps in order). A service of 150–400 lines is perfectly fine.
- Use Java records for DTOs and API request/response types; keep related small records together
  (e.g. nested records inside the service that uses them, or one Dtos.java file per feature).
- Write descriptive names (no abbreviations like "mgr", "svc", "tmp2").
- Add a short Javadoc to every class explaining WHAT it does and WHERE it sits in the pipeline,
  and a comment above every non-obvious method/block explaining WHY.
- Keep methods readable (roughly up to 40–60 lines); extract a private method only when it truly
  improves clarity or is reused.
- Constructor injection only. Use final fields.
- One clear exception strategy: a single ClipperException (runtime) + one @RestControllerAdvice.
- Log at the start/end of each pipeline stage with jobId and duration (SLF4J).
- Put LLM prompts in resources/prompts/*.txt, not in Java strings.
- Python worker: PEP 8, type hints, docstrings, a single file with clearly sectioned functions.
- Frontend: a few clear components (about 6–10 total), one api.ts for all backend calls,
  functional components with hooks, no global state library.

DO NOT:
- Do NOT create an interface for a class that has only one implementation.
- Do NOT create Impl classes, factories, builders, strategies, adapters or "managers" unless
  there is a real, current need.
- Do NOT split code into many 10–30 line classes or files.
- Do NOT add MapStruct, Lombok, Spring AI, Kafka, Redis, Docker for the backend, Spring Security,
  microservices or any dependency not listed in this prompt without asking first.
- Do NOT add speculative features, layers or abstractions "for the future".
- Do NOT leave TODOs, placeholder code or commented-out code.

## 7. Testing
Meaningful unit tests only (JUnit 5, AssertJ):
- Transcript chunk merging (timestamp offsets).
- Clip validation (clamping, overlaps, word snapping).
- .ass caption generation (timing and formatting).
- Gemini JSON response parsing.
- Hashtag/keyword normalisation and title length limits.
No tests that just check getters or mock everything.

## 8. Low-end machine requirements
- Never re-encode video locally (only the audio extraction step encodes).
- Stream uploads and downloads; never load whole videos into memory.
- README must include run flags: -Xmx384m -XX:+UseSerialGC and spring.main.lazy-initialization=true.
- Option to auto-delete source/segments after N days (configurable, default off).

## 9. Deliverables
1. Working backend, frontend and Modal worker in the structure below.
2. README.md containing:
   - Prerequisites: Java 21, Node, ffmpeg, yt-dlp, Modal account, ngrok (free static domain).
   - Setup, environment variables (with what each one is for), how to deploy the worker
     (modal deploy), and how to run everything.
   - "How the pipeline works" section with a short file-by-file guide.
   - "Developer app setup" section with step-by-step instructions for each platform:
     - Google Cloud (YouTube): create project, enable YouTube Data API v3, OAuth consent screen
       (External, add yourself as test user, publish to "In production" to avoid 7-day token
       expiry), create a Web OAuth client, scopes youtube.upload + youtube, redirect URL
       {PUBLIC_BASE_URL}/api/accounts/youtube/callback, and a note about the API audit
       (unaudited projects can only upload private videos; publishAt won't work until approved).
     - Meta (Instagram): switch Instagram to a Creator/Business account, create a Business app,
       add "Instagram API with Instagram Login", scopes instagram_business_basic +
       instagram_business_content_publish, redirect URL
       {PUBLIC_BASE_URL}/api/accounts/instagram/callback, add yourself as Instagram Tester and
       accept the invite in the Instagram app, keep the app in Development mode.
     - TikTok: create a developer app, add Login Kit + Content Posting API (Direct Post),
       scopes user.info.basic + video.publish, redirect URL
       {PUBLIC_BASE_URL}/api/accounts/tiktok/callback (HTTPS required), Privacy Policy and
       Terms URLs, sandbox testing, and a note that posts stay private (SELF_ONLY) until the
       app passes review.
     - Cloudflare R2: create bucket, enable public access (r2.dev URL), create an API token with
       Object Read & Write for that bucket only, and an optional 1-day lifecycle rule.
     - Where each copied value goes in .env.
   - "First test" checklist: connect each account, schedule a private test post 5 minutes ahead,
     check that tokens still work the next day.
 3. .env.example (with all variables from Section 5) and a sample application.yml with comments
   on every setting.

## 10. Work plan (complete and verify each milestone before the next)
M1: Project setup, DB entities, upload/URL ingest, job list, SSE progress.
M2: Audio extraction + Groq transcription (+ tests).
M3: Gemini clip selection + titles/description/hashtags + validation (+ tests).
M4: Local cut + CaptionService (+ tests).
M5: Modal worker + RenderClient + parallel rendering.
M6: Frontend pages + clip editor + re-render + metadata editing/copy.
M7: Scheduled posting — YouTube first, then Instagram, then TikTok (see 3b).
M8: README, cleanup, final review against Section 6.
After each milestone, give a short summary of what was built and how to test it.

## 11. Required project structure
(Use this structure. Do not add extra packages or files without a clear reason.)
clipper/
  backend/   Spring Boot packages: config, api, job, pipeline, publish, common;
             resources/prompts (clip-selection.txt, clip-metadata.txt)
             api/ also contains AccountController and ScheduleController
             publish/ contains ScheduledPost, ConnectedAccount (+ repositories),
             OAuthTokenService, PostSchedulerService, YouTubePublisher,
             InstagramPublisher, TikTokPublisher
  render-worker/  render_worker.py, requirements.txt, fonts/
  frontend/  src/pages (HomePage, JobPage, SchedulePage, SettingsPage),
             src/components (UploadForm, ProgressBar, ClipCard, ClipEditor, ScheduleDialog),
             src/api.ts, src/types.ts
  data/      runtime only, git-ignored
  README.md, .env.example, .gitignore