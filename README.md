# Clipper

Personal tool that turns long videos into short vertical clips with captions. The full specification is in
[SPEC.md](SPEC.md). This README covers milestone M1 (project setup, ingest, job list, live progress); the
complete README follows in M8.

## What M1 does

- Start a job from a **link** (YouTube and other sites via yt-dlp, or a public **Google Drive** file link) or
  by **uploading** a file (up to 20 GB; streamed to disk, never held in memory).
- The video is saved to `data/jobs/{jobId}/`, checked with ffprobe, and its length recorded.
- One job runs at a time; others wait as "Waiting".
- The job page shows live progress (Server-Sent Events).
- Jobs interrupted by a restart are marked FAILED with "Interrupted by restart".

## Prerequisites

| Tool | Windows | Linux |
| --- | --- | --- |
| Java 21 | `winget install EclipseAdoptium.Temurin.21.JDK` | `sudo apt install openjdk-21-jdk` |
| Maven | `winget install Apache.Maven` | `sudo apt install maven` |
| Node.js 20+ | `winget install OpenJS.NodeJS.LTS` | from nodejs.org or your package manager |
| FFmpeg (incl. ffprobe) | `winget install Gyan.FFmpeg` | `sudo apt install ffmpeg` |
| yt-dlp | `winget install yt-dlp.yt-dlp` | `pipx install yt-dlp` |

Open a new terminal after installing so the tools are on PATH. Keep yt-dlp up to date (`yt-dlp -U`),
because YouTube changes often.

## Run

1. Build the frontend once (and again after frontend changes):

   ```
   cd frontend
   npm install
   npm run build
   ```

2. Set the access token (any long random string of letters and digits):

   - Windows (PowerShell): `$env:APP_ACCESS_TOKEN = "your-long-random-token"`
   - Linux: `export APP_ACCESS_TOKEN=your-long-random-token`

3. Build and start the backend, which also serves the frontend (one process):

   ```
   cd backend
   mvn package
   java -Xmx384m -XX:+UseSerialGC -jar target/clipper.jar --spring.main.lazy-initialization=true
   ```

4. Open http://localhost:8080 and log in with the token.

The database and all videos live in `backend/data/` (git-ignored). Settings are in
`backend/src/main/resources/application.yml`; every setting has a comment.

For frontend work only: run the backend as above, then `npm run dev` in `frontend/` and open
http://localhost:5173 (API calls are proxied to port 8080).

## Testing M1

`mvn test` in `backend/` runs the unit tests. Then check by hand:

- Upload a small video: the job reaches Done and shows the right length.
- Upload a 3-5 GB file: the upload bar moves, Java's memory in Task Manager stays under about 450 MB,
  and `backend/data/jobs/{id}/` holds a file of the same size.
- Paste a YouTube link: the job page shows live percent, speed and time left; the file is 1080p or lower.
- Paste a Google Drive link to a video shared as "Anyone with the link": it downloads the same way.
- Open the same job in two tabs: both update. Start two jobs: the second waits, then runs.
- Stop Clipper during a download and start it again: that job shows "Interrupted by restart".
- `curl http://localhost:8080/api/jobs` answers 401; with `-H "X-Access-Token: <token>"` it answers 200.
- Start Clipper without yt-dlp on PATH: the log says so, and YouTube links are refused with a clear message.
- Delete a finished job: it leaves the list and its folder is gone.

## Project layout

```
backend/    Spring Boot app (packages: config, api, job, pipeline, common)
frontend/   React + Vite + TypeScript + Tailwind
data/       runtime data, git-ignored
SPEC.md     the specification
```
