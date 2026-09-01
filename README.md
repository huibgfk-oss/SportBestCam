# 🎥 SportBestCam

> **Plug-and-play video streaming, match automation, and analytics platform for sports venues and clubs.**

SportBestCam is a complete software and hardware solution designed to transform how matches are recorded, live-streamed, and analyzed for soccer, tennis, basketball, and other team or individual sports.

---

## 🚀 Key Features

* **📺 Automated Live Streaming:** Quick setup with scheduled auto start/stop functions synced to court bookings (RTMP / HLS support).
* **🎯 AI Highlights Generation:** Automated detection of key moments (goals, highlights) for fast video clip generation.
* **📊 Overlays & Live Scoreboard:** Professional graphics integration on stream (live score, match timer, team badges, sponsor logos).
* **🔒 Cloud Storage & Replays:** Automated full-match cloud archiving with secure sharing links for players, parents, and coaches.
* **💳 Monetization Module:** Built-in support for Pay-Per-View (PPV) streams and monthly subscriptions for fans and families.
* **📱 Multi-Device UI:** Intuitive web admin dashboard for venue operators and a responsive mobile interface for athletes.

---

## 🛠️ Tech Stack & Architecture

* **Frontend:** React / Next.js / Tailwind CSS
* **Backend:** Node.js / Python (FastAPI for video processing & AI models)
* **Streaming Engine:** FFmpeg, RTMP Server / AWS IVS / Ant Media Server
* **Database & Cache:** PostgreSQL / Redis (caching & real-time sockets)
* **Hardware Compatibility:** IP Cameras (RTSP/ONVIF standard), PTZ cameras, and dedicated AI vision hardware.

---

## 📦 Quick Start & Setup

### Prerequisites
* Node.js >= 18.x
* Python >= 3.10
* Installed FFmpeg on system path
* Running PostgreSQL & Redis instances

### 1. Clone the repository
```bash
git clone [https://github.com/huibgfk-oss/SportBestCam.git](https://github.com/huibgfk-oss/SportBestCam.git)
cd SportBestCam
