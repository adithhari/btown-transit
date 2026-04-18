# BT Transit

A real-time Android transit app for Bloomington, IN.

---

## Features

### Home Screen
- Greets you by time of day
- Instantly shows every bus arriving at stops within **600 metres** of your location
- Displays route badge, stop name, and a live countdown that ticks every 2 seconds
- No searching, no setup — live data the moment you open the app

### Ready to Go
- Save any stop as a **Favourite**
- Tap **Ready to Go** to instantly pull up a live tracker for the next bus to that stop
- Colour-coded progress bar fills as the bus approaches: blue (15 min) → orange (10 min) → red (5 min)
- Push notifications fire at each milestone so you don't need to keep the app open

### Live Map
- Full-screen map showing every active bus in the city
- Updates every **2 seconds**
- Each bus icon sits on the road, coloured by its route
- Filter by route to focus on the buses you care about

### Trip Planner
- Find the best route between any two stops
- **Leave at** mode — departs from now or a chosen time
- **Arrive by** mode — works backwards from a deadline to find the trip that gets you there on time
- Handles direct routes and transfers; eliminates dominated journeys automatically

### Bus Tracker
- Tap any bus on the map to open its live tracker
- See the bus move in real time with all upcoming stops and ETAs listed
- Tap a stop to lock in an arrival alert
- Progress bar and milestone notifications at 15, 10, and 5 minutes out

### Push Notifications
- Ongoing live-tracking notification with progress bar (no sound on updates)
- High-priority milestone alerts with vibration at 15, 10, and 5 minutes
- Journey countdown notifications for Favourite-stop trips

---

## Tech Stack

- **Language:** Kotlin
- **Architecture:** MVVM + Clean Architecture (Fragments, ViewModels, UseCases, Repository)
- **Maps:** Google Maps SDK
- **Data:** GTFS Static (ZIP) + GTFS-RT protobuf (vehicle positions + trip updates)
- **Networking:** Retrofit2 + OkHttp3
- **Async:** Kotlin Coroutines + StateFlow + `repeatOnLifecycle`
- **UI:** Material3, Jetpack Navigation, ViewBinding
- **Notifications:** Android NotificationManager with three channels (arrival alerts, live tracking, journey countdown)

---

## Data Sources

- **GTFS Static:** `https://bt.bloomington.in.gov/gtfs/google_transit.zip`
- **GTFS-RT:** `https://bt.bloomington.in.gov/gtfsrt/` (vehicle positions + trip updates, protobuf)
- No API key required for Bloomington Transit feeds

---

## Build

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
