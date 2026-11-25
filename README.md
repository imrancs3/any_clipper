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

You do **not** need to install Maven: the backend includes the Maven wrapper (`mvnw.cmd` on Windows,
`./mvnw` on Linux), which downloads the right Maven version the first time it runs.

| Tool | Windows | Linux |
| --- | --- | --- |
| Java 21 (JDK) | `winget install EclipseAdoptium.Temurin.21.JDK` | `sudo apt install openjdk-21-jdk` |
| Node.js 20+ | `winget install OpenJS.NodeJS.LTS` | from nodejs.org or your package manager |
| FFmpeg (incl. ffprobe) | `winget install Gyan.FFmpeg` | `sudo apt install ffmpeg` |
| yt-dlp | `winget install yt-dlp.yt-dlp` | `pipx install yt-dlp` |

Chocolatey works too if you prefer it (`choco install temurin21 nodejs-lts ffmpeg yt-dlp`, in an
Administrator terminal).

**After installing, close the terminal and open a new one** so the new tools are on PATH. Then check:

```
java -version      (must say 21)
node -v
ffmpeg -version
ffprobe -version
yt-dlp --version
```

If `java` is not found or shows another version on Windows, set `JAVA_HOME` to the JDK folder
(e.g. `C:\Program Files\Eclipse Adoptium\jdk-21...`) and add `%JAVA_HOME%\bin` to PATH under
Settings > System > About > Advanced system settings > Environment Variables. The Maven wrapper needs
`java` on PATH or `JAVA_HOME` set. Keep yt-dlp up to date (`yt-dlp -U`), because YouTube changes often.

## Run

1. Build the frontend once (and again after frontend changes):

   ```
   cd frontend
   npm install
   npm run build
   ```

2. Create your settings file: copy `.env.example` (in the project root) to `.env` and edit it:

   ```
   APP_ACCESS_TOKEN=your-long-random-token
   SERVER_PORT=8089
   ```

   Windows: `copy .env.example .env`, then `notepad .env`. Linux: `cp .env.example .env`.
   `SERVER_PORT` is only needed if port 8080 is busy on your PC. Both the backend and `npm run dev` read
   this one file, so no other file needs editing (the `8080` you see in `application.yml` and
   `vite.config.ts` is only the fallback). A real environment variable with the same name overrides `.env`.

3. Build and start the backend, which also serves the frontend (one process):

   Windows:

   ```
   cd backend
   .\mvnw.cmd package
   java -Xmx384m -XX:+UseSerialGC -jar target\clipper.jar --spring.main.lazy-initialization=true
   ```

   Linux:

   ```
   cd backend
   ./mvnw package
   java -Xmx384m -XX:+UseSerialGC -jar target/clipper.jar --spring.main.lazy-initialization=true
   ```

   The first build downloads Maven and the libraries, so it takes a few minutes. If you already have
   Maven installed, `mvn package` works the same way.

4. Open http://localhost:8080 (or your `SERVER_PORT`, e.g. http://localhost:8089) and log in with the token.
   The console prints the exact address once Clipper is ready ("Clipper is ready: open http://localhost:...").

The database and all videos live in `backend/data/` (git-ignored). Settings are in
`backend/src/main/resources/application.yml`; every setting has a comment.

For frontend work only: run the backend as above, then `npm run dev` in `frontend/` and open
http://localhost:5173. API calls are passed on to the backend at the `SERVER_PORT` from `.env`. For normal use you don't need `npm run dev` at all: open the backend's own address.

## Testing M1

`.\mvnw.cmd test` (Windows) or `./mvnw test` (Linux) in `backend/` runs the unit tests. Then check by hand:

- Upload a small video: the job reaches Done and shows the right length.
- Upload a 3-5 GB file: the upload bar moves, Java's memory in Task Manager stays under about 450 MB,
  and `backend/data/jobs/{id}/` holds a file of the same size.
- Paste a YouTube link: the job page shows live percent, speed and time left; the file is 1080p or lower.
- Paste a Google Drive link to a video shared as "Anyone with the link": it downloads the same way.
- Open the same job in two tabs: both update. Start two jobs: the second waits, then runs.
- Stop Clipper during a download and start it again: that job shows "Interrupted by restart".
- `curl http://localhost:8080/api/jobs` (use your port) answers 401; with `-H "X-Access-Token: <token>"` it answers 200.
- Start Clipper without yt-dlp on PATH: the log says so, and YouTube links are refused with a clear message.
- Delete a finished job: it leaves the list and its folder is gone.

## Project layout

```
backend/    Spring Boot app (packages: config, api, job, pipeline, common)
frontend/   React + Vite + TypeScript + Tailwind
data/       runtime data, git-ignored
SPEC.md     the specification
```
