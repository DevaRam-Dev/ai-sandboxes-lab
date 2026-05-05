# Frontend — Chart Generator

React + Vite chat-style UI that sends natural-language prompts to the Spring Boot backend and renders the returned PNG charts inline.

## Prerequisites

- Node 20.19+ or 22.12+
- Spring Boot backend running on `http://localhost:8080` (the `basic-hello-world/` project)

## Install

```bash
cd frontend
npm install
```

## Run dev server

```bash
npm run dev
```

Opens at **http://localhost:5173**. Requests to `/api/*` are proxied to `http://localhost:8080`, so no CORS configuration is needed on the backend.

The backend must be running on port 8080 before you send any prompts.

## Usage

1. Type a prompt in the textarea (e.g. *"Plot bar chart from Jan 2026 to March 2026"*)
2. Press **Enter** or click **Send**
3. A loading spinner with an elapsed-seconds counter appears — chart generation takes 15–45 s
4. The chart PNG renders inline when ready; click it to view full-size (Escape or click backdrop to close)

## Build for production

```bash
npm run build
```

Output goes to `dist/`. Serve it with any static file server, or deploy behind the same host as the backend so the `/api` prefix routes correctly.
