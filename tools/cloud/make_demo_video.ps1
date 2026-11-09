# Joins the frames and sound made by CloudDemoVideoTest into docs/cloud-demo.mp4 (and a poster).
#   $env:DEMO_VIDEO = "1"; .\gradlew.bat :app:testDebugUnitTest --tests '*CloudDemoVideoTest*'
#   powershell -ExecutionPolicy Bypass -File tools\cloud\make_demo_video.ps1
# Encoding: H.264 Main, no B-frames, AAC 48 kHz, so the Windows media player opens it.
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$demo = Join-Path $repo 'app\build\demo'
$timing = @{}
foreach ($line in Get-Content (Join-Path $demo 'timing.txt')) { $k, $v = $line -split ' '; $timing[$k] = [double]$v }
$introMs = [int]($timing['intro'] * 1000)
$total = $timing['video']

# A line that stays on screen: how the video was made.
[IO.File]::WriteAllText((Join-Path $demo 'note.txt'), '화면: 앱 Compose UI를 JVM에서 렌더링 · 소리: 앱 오디오 엔진 실제 출력 · 에뮬레이터 녹화 아님', (New-Object Text.UTF8Encoding $false))

Push-Location $demo
try {
    $font = 'C\:/Windows/Fonts/malgun.ttf'
    # The app's screen stays whole: a strip above holds the note, a strip below the captions.
    $video = "fps=30,scale=1280:-2:flags=lanczos,pad=iw:ih+112:0:36:color=0x060d14," +
        # SRT styles are in a 288-line space (x3.6 here): size 8 is about 29 px, inside the 76 px strip.
        "subtitles=captions.srt:force_style='FontName=Malgun Gothic,FontSize=8,PrimaryColour=&H00FFFFFF,BorderStyle=1,Outline=0,Shadow=0,MarginV=6,Bold=1'," +
        "drawtext=fontfile='$font':textfile=note.txt:fontsize=16:fontcolor=0x9fb4c2:x=(w-tw)/2:y=9"
    $audio = "[1:a]adelay=${introMs}|${introMs},aresample=48000,apad[a]"
    & ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i frames.txt -i drive.wav `
        -filter_complex "[0:v]$video[v];$audio" -map '[v]' -map '[a]' -t $total `
        -c:v libx264 -profile:v main -level 4.0 -pix_fmt yuv420p -bf 0 -refs 1 -crf 22 -preset slow `
        -c:a aac -ar 48000 -b:a 160k -movflags +faststart (Join-Path $repo 'docs\cloud-demo.mp4')
    if ($LASTEXITCODE -ne 0) { throw 'ffmpeg failed' }
    # Poster: the drive at speed, every stage playing.
    & ffmpeg -y -hide_banner -loglevel error -ss ([math]::Min($total - 2, $timing['intro'] + 50)) -i (Join-Path $repo 'docs\cloud-demo.mp4') -frames:v 1 (Join-Path $repo 'docs\cloud-demo-poster.png')
    if ($LASTEXITCODE -ne 0) { throw 'poster failed' }
} finally { Pop-Location }
Get-Item (Join-Path $repo 'docs\cloud-demo.mp4'), (Join-Path $repo 'docs\cloud-demo-poster.png') | Select-Object Name, Length
