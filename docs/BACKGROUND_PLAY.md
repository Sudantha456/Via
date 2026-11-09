# Background Play — ඕනෑම site එකක video / audio background play

Via Background Play යනු මේ repository එකේ ඇති Android app එකයි. එය WebView
පාදක browser එකක් වන අතර, එහි ප්‍රධාන අරමුණ **screen එක off වුණත්, app එක
background එකේ තිබුණත් video/audio දිගටම play වීම** යි.

## 1. එක බැල්මෙන් features

| Feature | විස්තරය |
|---|---|
| Background play | ඕනෑම website එකක `<video>` / `<audio>` element එකක් screen off වුණත් දිගටම ධාවනය වේ |
| YouTube | `m.youtube.com` සහ `youtube.com` සඳහා වෙන් වූ adapter එකක් (title, channel name, thumbnail, playlist next/prev) |
| YouTube Music | `music.youtube.com` සඳහා වෙන් වූ adapter එකක් (song, artist, album art, next/prev) |
| අනෙක් sites | SoundCloud, Spotify Web, Bandcamp + ඕනෑම generic HTML5 player එකක් |
| Rich notification | Thumbnail, title, artist/owner, album, **site name**, previous, play/pause, next, seek bar, −15s/+15s |
| Lock screen / Bluetooth | `MediaSessionCompat` හරහා lock screen, headset, Bluetooth, Android Auto controls |
| Mini player + Full player | App එක ඇතුළේ mini player එකක් සහ swipe-down කරන full player sheet එකක් |
| Multi-tab audio | Tabs කිහිපයක් play වුවහොත් next/prev මගින් tabs අතර මාරු වේ |
| Headset unplug | Headphone එක ගලවන විට auto pause (Android standard) |
| Battery | Wake lock + foreground service + "ignore battery optimisation" shortcut |

## 2. Background play වැඩ කරන ආකාරය

1. **Injected engine** (`app/src/main/assets/bgp_engine.js`) සෑම page එකකටම
   `onPageFinished` වලදී inject වේ. එය:
   - හොඳම media element එක තෝරාගනී (`video`, `audio`, iframe තුළ ඇති ඒවා පවා),
   - site-specific selectors මගින් title / artist / artwork / duration එකතු කරගනී,
   - `navigator.mediaSession.metadata` හරහා site එක විසින්ම දෙන metadata එකත් ගනී,
   - Page Visibility API (`document.hidden`, `visibilityState`, `blur`) neutralise කරයි,
   - pause වුණු playback එක නැවත resume කරයි.
2. **Bridge** (`PlaybackBridge`) මගින් එම metadata JSON ලෙස native layer එකට එවයි.
3. **`PlaybackCenter`** සියලු tabs වලින් එන දත්ත එකට එකතු කර "දැන් play වන්නේ කුමක්ද"
   යන්න තීරණය කරයි.
4. **`BackgroundPlaybackService`** foreground service එකක් ලෙස notification එක සහ
   `MediaSession` එක පවත්වාගෙන යයි. Screen off වුණත් process එක මරා නොදමන නිසා
   WebView එකේ media pipeline එක දිගටම ක්‍රියා කරයි.
5. **Controls** (notification / lock screen / headset / mini player) → `PlaybackController`
   → tab එකේ JS → `window.__bgp.act('play' | 'pause' | 'next' | 'prev' | 'seek')`.

## 3. Notification එකේ තොරතුරු

```
┌─────────────────────────────────────────────┐
│ [thumbnail]  Song title                     │
│              Artist · Album · YouTube Music │
│              ──────●────────────            │
│              [⏮]   [⏸]   [⏭]                │
└─────────────────────────────────────────────┘
```

- **Thumbnail** — video frame එක හෝ album art එක (og:image, poster, MediaSession artwork)
- **Title** — song / video නම
- **Artist / owner** — channel name හෝ artist
- **Site name** — `YouTube`, `YouTube Music`, `SoundCloud` … (notification sub-text ලෙස)
- **Previous / Play-Pause / Next** — compact view එකේ පෙනෙන actions
- **Seek bar** — duration එක දන්නා විට progress + lock screen scrubber
- **Tap** — play වන tab එකට කෙලින්ම යයි

## 4. Build කරන ආකාරය (GitHub Actions)

`.github/workflows/build-apk.yml` workflow එක `push` හෝ `workflow_dispatch` වලදී
APK එක build කර:

1. **Artifacts** → `via-background-play-apk` (Actions run එකේ පහළින් download කරගන්න)
2. **Releases** → `build-<run number>` tag එක සහිත release එකක (කෙලින්ම install කළ හැකි APK)

Release build එක standard debug key එකෙන් sign වේ — එබැවින් secrets අවශ්‍ය නැති අතර
APK එක කෙලින්ම sideload කළ හැක.

Local build:

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/
./gradlew assembleDebug       # app/build/outputs/apk/debug/
```

`gradlew` ස්ක්‍රිප්ට් එක මුලින්ම Gradle 8.7 බාගෙන cache කරගනී (JDK 17 පමණක් අවශ්‍ය).

## 5. Installation සහ පළමු setup

1. Release එකෙන් `app-release.apk` බාගන්න.
2. "Install unknown apps" allow කරන්න, install කරන්න.
3. App එක open කර **Notification permission** එක allow කරන්න (Android 13+).
4. Settings → **Ignore battery optimisations** → Allow. (මෙය නොකළොත් සමහර phones
   වල minutes කිහිපයකින් playback නතර වේ.)
5. YouTube / YouTube Music open කර video එකක් play කරන්න → app එකෙන් එළියට යන්න
   හෝ screen off කරන්න → notification එකේ controls පෙනෙනු ඇත.

## 6. නිතර අසන ගැටළු

**Q: Notification එකේ thumbnail එක පෙනෙන්නේ නැත්නම්?**
සමහර sites CDN එකෙන් image එකට referer check කරයි. එවිට app එකේ icon එක fallback
වේ. Title/artist/site name තවමත් නිවැරදිව පෙනේ.

**Q: Screen off කළ විට නතර වෙනවා නම්?**
Settings → Battery → "Ignore battery optimisations" on කරන්න, සහ phone එකේ
"Battery saver" / "App standby" වලින් Via Background Play එක exempt කරන්න.

**Q: YouTube app එකේ Premium background play එකට මෙය වෙනස්ද?**
ඔව්. මෙය YouTube Premium නොමැතිව web player එක හරහා background play ලබා දෙයි.

**Q: Data ඉතිරි කරගන්නේ කොහොමද?**
Settings → "Mute hidden videos" on කරන්න. Tab එක cover වුණත් audio stream එක පමණක්
දිගටම යයි.
