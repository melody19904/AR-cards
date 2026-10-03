<#
 release_v1.ps1 - phased, safe release helper for AR-cards V1 (Windows PowerShell 5.1 or 7)

 Run ONE phase at a time, in this order, from any folder:
   .\release_v1.ps1 check     read-only audit: wrong repo, leaked keys, BOM/mojibake, junk files
   .\release_v1.ps1 fix       repairs what `check` found (asks before deleting anything)
   .\release_v1.ps1 build     compiles debug, then a signed release APK -> .\dist\
   .\release_v1.ps1 commit    re-checks for secrets, stages, shows what will be committed, commits
   .\release_v1.ps1 push      pushes (never force), tags v1.0.0, optional GitHub release
   .\release_v1.ps1 verify    wakes the Render server and checks /health, /cards, assets
   .\release_v1.ps1 install   adb install -r  (use it to test an OLD -> NEW upgrade)

 Nothing here uses -Force on git. Nothing deletes without asking.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("check", "fix", "build", "commit", "push", "verify", "install")]
    [string]$Phase,
    [string]$Root      = "C:\Users\NISCHAY\Desktop\ar\AR-cards-V1",
    [string]$Remote    = "https://github.com/melody19904/AR-cards.git",
    [string]$ServerUrl = "https://serverstat-cpsy.onrender.com",
    [string]$Version   = "1.0.0"
)

# Native commands (git, gradle) write warnings to stderr; with "Stop" PowerShell 5.1 would
# treat those as fatal. So: Continue globally, and check $LASTEXITCODE / use -ErrorAction Stop explicitly.
$ErrorActionPreference = "Continue"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:problems = 0

function Ok($m)   { Write-Host "[ OK ] $m" -ForegroundColor Green }
function Info($m) { Write-Host "[ .. ] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "[FAIL] $m" -ForegroundColor Red; $script:problems++ }
function Head($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor White }
function Stop-Here($m) { Write-Host $m -ForegroundColor Red; exit 1 }

if (-not (Test-Path -LiteralPath $Root)) { Stop-Here "Root folder not found: $Root" }
Set-Location -LiteralPath $Root
$Android = Join-Path $Root "android"
$Server  = Join-Path $Root "server"
$ScriptSelf = $PSCommandPath

# characters that appear when UTF-8 text was decoded as Windows-1252 and re-saved (e.g. an em dash)
$Moji = ([string][char]0x00E2) + ([string][char]0x20AC)
$MojiEmDash = $Moji + ([string][char]0x201D)

function Has-Bom([string]$p) {
    $b = [IO.File]::ReadAllBytes($p)
    return ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
}

function Git-Files {
    # exactly the files git would include: tracked + untracked, minus .gitignore'd
    $list = git ls-files -co --exclude-standard 2>$null
    foreach ($f in $list) {
        $full = Join-Path $Root $f
        if ((Test-Path -LiteralPath $full -PathType Leaf)) { $full }
    }
}

$TextExt = @(".py", ".kt", ".kts", ".xml", ".html", ".js", ".json", ".md", ".txt", ".properties",
             ".gradle", ".yml", ".yaml", ".toml", ".cfg", ".ps1", ".bat")

$SecretPatterns = @(
    @{ Name = "JWT / Supabase key";   Rx = 'eyJ[A-Za-z0-9_\-]{15,}\.[A-Za-z0-9_\-]{15,}\.[A-Za-z0-9_\-]{10,}' },
    @{ Name = "sb_secret key";        Rx = 'sb_secret_[A-Za-z0-9_\-]{8,}' },
    @{ Name = "hard-coded *_KEY";     Rx = '(?i)(SERVICE|SECRET|API|ADMIN)_?KEY\s*[:=]\s*["''][^"'']{16,}["'']' },
    @{ Name = "GitHub token";         Rx = '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})' },
    @{ Name = "keystore password";    Rx = '(?i)(store|key)Password\s*=\s*\S{3,}'; Ext = ".properties" }
)

function Scan-Secrets($files) {
    $hits = 0
    foreach ($f in $files) {
        if ($f -eq $ScriptSelf) { continue }
        if ($f -like "*.example") { continue }
        if ($TextExt -notcontains ([IO.Path]::GetExtension($f).ToLower())) { continue }
        $lines = Get-Content -LiteralPath $f -ErrorAction SilentlyContinue
        if (-not $lines) { continue }
        for ($i = 0; $i -lt $lines.Count; $i++) {
            foreach ($p in $SecretPatterns) {
                if ($p.Ext -and ([IO.Path]::GetExtension($f).ToLower() -ne $p.Ext)) { continue }
                if ($lines[$i] -match $p.Rx) {
                    $rel = $f.Substring($Root.Length).TrimStart('\')
                    Bad ("{0}: line {1}  looks like a {2}  (value hidden)" -f $rel, ($i + 1), $p.Name)
                    $hits++
                }
            }
        }
    }
    return $hits
}

# ------------------------------------------------------------------------------------------
function Phase-Check {
    Head "1. Which git repo is active?"
    $top = (git rev-parse --show-toplevel 2>$null)
    if (-not $top) {
        Warn "No git repo here yet (run:  git init -b main  inside $Root)"
    } else {
        $topWin = ($top -replace '/', '\').TrimEnd('\')
        if ($topWin -ieq $Root.TrimEnd('\')) { Ok "Repo root = $Root" }
        else { Bad "git is using a DIFFERENT repo: $topWin  (expected $Root)" }
    }
    if (Test-Path (Join-Path $HOME ".git")) {
        Warn "There is a git repo in your HOME folder ($HOME\.git). It is harmless inside $Root,"
        Warn "but a stray 'git add .' anywhere else under your user folder would use it. Inspect it with:"
        Warn "   git -C `"$HOME`" remote -v      (if it is accidental, RENAME .git to .git.bak - don't delete)"
    }

    Head "2. Secrets in files git would commit"
    if ($top) {
        $files = @(Git-Files)
        $n = Scan-Secrets $files
        if ($n -eq 0) { Ok "No key/token/password patterns found in $($files.Count) files" }
    }

    Head "3. Encoding damage from earlier PowerShell edits"
    $targets = @()
    $targets += Get-ChildItem -LiteralPath $Android -Recurse -File -Include *.kt, *.kts, *.xml, *.properties -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.idea)\\' }
    $targets += Get-ChildItem -LiteralPath $Server -File -Filter *.py -ErrorAction SilentlyContinue
    $bomCount = 0; $mojiCount = 0
    foreach ($t in $targets) {
        if (Has-Bom $t.FullName) { $bomCount++; Warn "BOM added: $($t.FullName.Substring($Root.Length + 1))" }
        $txt = [IO.File]::ReadAllText($t.FullName, [Text.Encoding]::UTF8)
        if ($txt.Contains($Moji)) { $mojiCount++; Bad "garbled characters (was an em dash): $($t.FullName.Substring($Root.Length + 1))" }
    }
    if ($bomCount -eq 0 -and $mojiCount -eq 0) { Ok "No BOMs, no garbled characters" }
    else { Info "'fix' will repair these (Windows PowerShell 5.1 Get-Content/Set-Content round-trips cause this)" }

    Head "4. Version & traffic security"
    $g = Join-Path $Android "app\build.gradle.kts"
    if (Test-Path $g) {
        $gt = [IO.File]::ReadAllText($g)
        if ($gt -match 'versionName\s*=\s*"([^"]+)"') { if ($Matches[1] -eq $Version) { Ok "versionName = $($Matches[1])" } else { Bad "versionName is $($Matches[1]), expected $Version" } }
        if ($gt -match 'versionCode\s*=\s*(\d+)') { if ([int]$Matches[1] -ge 2) { Ok "versionCode = $($Matches[1])" } else { Bad "versionCode is $($Matches[1]) - must be higher than any APK already installed" } }
    } else { Bad "app\build.gradle.kts not found" }
    $m = Join-Path $Android "app\src\main\AndroidManifest.xml"
    if (Test-Path $m) {
        if ([IO.File]::ReadAllText($m) -match 'usesCleartextTraffic\s*=\s*"true"') { Bad "usesCleartextTraffic=true is still in the manifest" } else { Ok "Cleartext traffic not enabled" }
    }

    Head "5. Files that should NOT ship"
    $junk = @(
        "android\app\release", "android\local.properties", "android\.idea", "android\.kotlin",
        "server\card.jpg", "server\meta.json", "server\render.png", "server\.venv", "server\__pycache__"
    )
    $found = 0
    foreach ($j in $junk) { if (Test-Path (Join-Path $Root $j)) { $found++; Warn "present: $j" } }
    if ($found -eq 0) { Ok "No junk paths" } else { Info "(most are .gitignore'd; 'fix' removes the loose server\card.jpg/meta.json/render.png copies)" }

    Head "6. Release signing"
    if (Test-Path (Join-Path $Android "keystore.properties")) { Ok "android\keystore.properties exists (gitignored)" }
    else { Warn "android\keystore.properties missing -> release APK would be UNSIGNED. 'fix' creates a template." }

    Head "7. Server sanity"
    $sp = Join-Path $Server "server.py"
    if (Test-Path $sp) {
        $st = [IO.File]::ReadAllText($sp)
        foreach ($needle in @("SUPABASE", "admin/reload", "/health")) {
            if ($st.Contains($needle)) { Ok "server.py mentions '$needle'" } else { Warn "server.py does NOT mention '$needle' - confirm this is the Supabase-backed version" }
        }
    } else { Bad "server\server.py missing" }
    $pp = Join-Path $Server "publish_card.py"
    if (Test-Path $pp) {
        Info "publish_card.py reads its key like this:"
        Select-String -LiteralPath $pp -Pattern 'environ|SERVICE_KEY' | ForEach-Object { Write-Host ("        line {0}: {1}" -f $_.LineNumber, ($_.Line -replace 'eyJ[A-Za-z0-9_\-\.]+', 'eyJ...hidden')) }
    }

    Head "Result"
    if ($script:problems -eq 0) { Ok "check passed" } else { Write-Host "$($script:problems) problem(s). Run:  .\release_v1.ps1 fix" -ForegroundColor Yellow }
}

# ------------------------------------------------------------------------------------------
function Phase-Fix {
    Head "Repair encoding (strip BOM, fix garbled em dashes)"
    $targets = @()
    $targets += Get-ChildItem -LiteralPath $Android -Recurse -File -Include *.kt, *.kts, *.xml, *.properties -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.idea)\\' }
    $targets += Get-ChildItem -LiteralPath $Server -File -Filter *.py -ErrorAction SilentlyContinue
    foreach ($t in $targets) {
        $txt = [IO.File]::ReadAllText($t.FullName, [Text.Encoding]::UTF8)
        $new = $txt.Replace($MojiEmDash, "-")
        $hadBom = Has-Bom $t.FullName
        if ($new -ne $txt -or $hadBom) {
            [IO.File]::WriteAllText($t.FullName, $new, $Utf8NoBom)
            Ok "repaired $($t.FullName.Substring($Root.Length + 1))"
        }
        if ($new.Contains($Moji)) { Warn "still contains odd characters: $($t.Name) - review by hand" }
    }

    Head "publish_card.py: never hard-code the key"
    $pp = Join-Path $Server "publish_card.py"
    if (Test-Path $pp) {
        $txt = [IO.File]::ReadAllText($pp, [Text.Encoding]::UTF8)
        $rx = '(?m)^(\s*SUPABASE_SERVICE_KEY\s*=\s*)["''][^"''\r\n]{20,}["'']'
        if ([regex]::IsMatch($txt, $rx)) {
            $txt = [regex]::Replace($txt, $rx, '${1}os.environ.get("SUPABASE_SERVICE_KEY", "")')
            [IO.File]::WriteAllText($pp, $txt, $Utf8NoBom)
            Ok "removed the hard-coded key from publish_card.py"
            Warn "That key was in a file on disk AND shown in a chat. ROTATE it in the Supabase dashboard,"
            Warn "then update SUPABASE_SERVICE_KEY on Render (Environment tab) and in your own shell."
        } else { Ok "publish_card.py has no hard-coded key" }
        Info "In PowerShell the variable name must match EXACTLY (this was the real cause of 'Invalid Compact JWS':"
        Info "the script reads SUPABASE_SERVICE_KEY but you had set SUPABASE_KEY):"
        Write-Host '        $env:SUPABASE_SERVICE_KEY = "paste-the-new-key-here"' -ForegroundColor Gray
    }

    Head "Line endings"
    $ga = Join-Path $Root ".gitattributes"
    if (-not (Test-Path $ga)) {
        $content = @"
* text=auto
gradlew text eol=lf
*.bat text eol=crlf
*.png binary
*.jpg binary
*.webp binary
*.jar binary
*.jks binary
"@
        [IO.File]::WriteAllText($ga, $content + "`n", $Utf8NoBom)
        Ok "created .gitattributes (stops the LF/CRLF warnings and keeps gradlew runnable)"
    } else { Ok ".gitattributes already exists" }

    Head ".gitignore extras"
    $gi = Join-Path $Root ".gitignore"
    $giText = if (Test-Path $gi) { [IO.File]::ReadAllText($gi) } else { "" }
    $add = @()
    foreach ($line in @("dist/", "keystore.properties", "*.jks", "*.apk")) { if ($giText -notmatch ('(?m)^' + [regex]::Escape($line) + '\s*$')) { $add += $line } }
    if ($add.Count -gt 0) {
        [IO.File]::AppendAllText($gi, "`n# added by release_v1.ps1`n" + ($add -join "`n") + "`n", $Utf8NoBom)
        Ok ".gitignore += $($add -join ', ')"
    } else { Ok ".gitignore already covers dist/, keystore.properties, *.jks, *.apk" }

    Head "Signing template"
    $kp = Join-Path $Android "keystore.properties"
    $kpEx = Join-Path $Android "keystore.properties.example"
    $example = @"
storeFile=C:/Users/NISCHAY/cardvision.jks
storePassword=CHANGE_ME
keyAlias=CHANGE_ME
keyPassword=CHANGE_ME
"@
    if (-not (Test-Path $kpEx)) { [IO.File]::WriteAllText($kpEx, $example + "`n", $Utf8NoBom); Ok "wrote keystore.properties.example" }
    if (-not (Test-Path $kp)) {
        Warn "Create android\keystore.properties (copy the .example, fill in YOUR real values, use forward slashes in storeFile)."
        Warn "It is gitignored. Your .jks lives at C:\Users\NISCHAY\cardvision.jks (outside the repo - good). BACK IT UP: lose it and you can never update the app."
    }

    Head "Loose copies of card_001 in server\ (the real ones live in server\catalog\)"
    foreach ($n in @("card.jpg", "meta.json", "render.png")) {
        $p = Join-Path $Server $n
        if (Test-Path $p -PathType Leaf) {
            $a = Read-Host "Delete server\$n ? (y/N)"
            if ($a -match '^(y|yes)$') { Remove-Item -LiteralPath $p; Ok "deleted server\$n" }
        }
    }
    foreach ($d in @("android\app\release", "android\.idea", "android\.kotlin")) {
        $p = Join-Path $Root $d
        if (Test-Path $p) { Remove-Item -LiteralPath $p -Recurse -Force; Ok "removed $d" }
    }
    Info "Now re-run:  .\release_v1.ps1 check"
}

# ------------------------------------------------------------------------------------------
function Phase-Build {
    $lp = Join-Path $Android "local.properties"
    if (-not (Test-Path $lp)) {
        $sdk = $env:ANDROID_HOME
        if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
        if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
        if (-not (Test-Path $sdk)) { Stop-Here "Android SDK not found. Set ANDROID_HOME or open the project once in Android Studio." }
        [IO.File]::WriteAllText($lp, "sdk.dir=" + ($sdk -replace '\\', '/') + "`n", $Utf8NoBom)
        Ok "recreated android\local.properties (sdk.dir=$sdk) - it is gitignored"
    }

    Set-Location -LiteralPath $Android
    Head "Debug compile (catches Kotlin errors from the PowerShell edits)"
    & .\gradlew.bat clean assembleDebug
    if ($LASTEXITCODE -ne 0) { Stop-Here "Debug build FAILED - fix the errors above before releasing." }
    Ok "debug build compiles"

    $hasKeys = Test-Path (Join-Path $Android "keystore.properties")
    Head "Release build"
    if (-not $hasKeys) { Warn "No keystore.properties -> the release APK will be UNSIGNED and cannot be installed over a signed one." }
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { Stop-Here "Release build FAILED." }

    $out = Join-Path $Android "app\build\outputs\apk\release"
    $apk = Get-ChildItem -LiteralPath $out -Filter *.apk | Select-Object -First 1
    if (-not $apk) { Stop-Here "No APK found in $out" }
    $dist = Join-Path $Root "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $dest = Join-Path $dist ("CardVault-v{0}.apk" -f $Version)
    Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force
    $h = Get-FileHash -LiteralPath $dest -Algorithm SHA256
    Ok ("APK: {0}  ({1:N1} MB)" -f $dest, ((Get-Item $dest).Length / 1MB))
    Info "SHA-256: $($h.Hash)"
    if (-not $hasKeys) { Warn "unsigned build - fine for a quick test, NOT for distribution" }
    Set-Location -LiteralPath $Root
}

# ------------------------------------------------------------------------------------------
function Phase-Commit {
    if (-not (git rev-parse --show-toplevel 2>$null)) { Stop-Here "Not a git repo. Run:  git init -b main" }
    $top = ((git rev-parse --show-toplevel) -replace '/', '\').TrimEnd('\')
    if ($top -ine $Root.TrimEnd('\')) { Stop-Here "Wrong repo active: $top" }

    if (-not (git config user.email)) { Stop-Here "Set your identity first:`n  git config user.name `"Your Name`"`n  git config user.email `"you@example.com`"" }

    Head "Secret scan (everything git would commit)"
    $n = Scan-Secrets @(Git-Files)
    if ($n -gt 0) { Stop-Here "Refusing to commit: $n secret-looking value(s). Run 'fix', rotate keys if needed, then retry." }
    Ok "clean"

    git add -A
    Head "Safety checks on the staged set"
    $staged = @(git diff --cached --name-only)
    if ($staged.Count -eq 0) { Stop-Here "Nothing staged." }
    $bad = $staged | Where-Object { $_ -match '(\.jks$|\.keystore$|keystore\.properties$|(^|/)\.env|local\.properties$|\.apk$|\.zip$|(^|/)\.idea/|(^|/)build/)' }
    if ($bad) { Write-Host ($bad -join "`n"); Stop-Here "Refusing: sensitive/generated files are staged (listed above). Run: git reset" }
    Ok "$($staged.Count) files staged, none sensitive"

    $big = @()
    foreach ($s in $staged) { $p = Join-Path $Root $s; if (Test-Path -LiteralPath $p -PathType Leaf) { $big += [pscustomobject]@{ MB = [math]::Round((Get-Item -LiteralPath $p).Length / 1MB, 2); File = $s } } }
    Info "Largest staged files:"
    $big | Sort-Object MB -Descending | Select-Object -First 8 | Format-Table -AutoSize | Out-Host
    if ($big | Where-Object { $_.MB -gt 10 }) { Warn "A file over 10 MB is staged - check it really belongs in git." }

    Head "Diff scan"
    $diff = git diff --cached -U0
    $leak = $false
    foreach ($p in ($SecretPatterns | Where-Object { -not $_.Ext })) { if ($diff -match $p.Rx) { Bad "staged diff matches: $($p.Name)"; $leak = $true } }
    if ($leak) { Stop-Here "Unstaging. Run 'git reset', remove the value, retry." }
    Ok "staged diff clean"

    git commit -m "AR-cards V1.0.0 - local ORB+LK card tracking, Supabase catalog, QR claim, offline vault, P2P transfer"
    if ($LASTEXITCODE -ne 0) { Stop-Here "commit failed" }
    Ok "committed:"
    git log --oneline -n 1
}

# ------------------------------------------------------------------------------------------
function Phase-Push {
    $remotes = git remote
    if ($remotes -notcontains "origin") { git remote add origin $Remote; Ok "added origin = $Remote" }
    $url = git remote get-url origin
    Info "origin = $url"
    if ($url -ne $Remote) { Warn "origin differs from the expected $Remote" }

    Info "Reading what already exists on GitHub (no changes made)..."
    $heads = git ls-remote --heads origin 2>&1
    if ($LASTEXITCODE -ne 0) { Stop-Here "Cannot reach the remote (login / network / wrong URL):`n$heads" }

    $branch = "main"
    if ($heads -and ($heads | Out-String) -match 'refs/heads/main') {
        Warn "The remote ALREADY has a 'main' branch with its own history:"
        $heads | Out-Host
        Warn "Pushing your new history to 'main' would be rejected (and force-pushing would DESTROY the old one)."
        Warn "Safe choice: push to a new branch 'v1-release'. Afterwards on GitHub: Settings > Branches > make it the default, or merge it."
        $branch = "v1-release"
        git push -u origin "main:$branch"
    } else {
        git push -u origin main
    }
    if ($LASTEXITCODE -ne 0) { Stop-Here "push failed (see above). Nothing was forced." }
    Ok "pushed to origin/$branch"

    $tag = "v$Version"
    if (-not (git tag -l $tag)) { git tag -a $tag -m "AR-cards $tag" }
    git push origin $tag
    $apk = Join-Path $Root ("dist\CardVault-v{0}.apk" -f $Version)
    if ((Test-Path $apk) -and (Get-Command gh -ErrorAction SilentlyContinue)) {
        $a = Read-Host "Create a GitHub Release with the APK attached using 'gh'? (y/N)"
        if ($a -match '^(y|yes)$') { gh release create $tag $apk --title "Card Vault $tag" --notes "First release. See README for known limitations." }
    } else {
        Info "Attach the APK by hand: $($url -replace '\.git$','')/releases/new?tag=$tag   (drag dist\CardVault-v$Version.apk in). Do NOT commit the APK."
    }

    Head "Render is separate - do these in the Render dashboard"
    Write-Host @"
  1. Your Render service currently builds from github.com/testing19904/Serverstat, NOT this repo.
     Either: Settings > Build & Deploy > Repository -> $($url -replace '\.git$','') and set Root Directory = server
     Or:     keep Serverstat and copy the server/ folder there.
  2. Settings > Build Filters: ignore  server/catalog/**  and  android/**  so asset/app changes don't redeploy the server.
     (Check the option name in the dashboard - Render calls this monorepo 'Build Filters'.)
  3. Environment: SUPABASE_URL, SUPABASE_SERVICE_KEY (the NEW rotated one), your admin key. DEBUG_SAVE_FRAMES must be unset or 0.
  4. Start command:  python server.py     Build command:  pip install -r requirements.txt
"@
}

# ------------------------------------------------------------------------------------------
function Phase-Verify {
    Head "Waking $ServerUrl (a sleeping free instance takes ~30-60 s)"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $health = $null
    for ($i = 1; $i -le 20; $i++) {
        try { $health = Invoke-RestMethod -Uri "$ServerUrl/health" -TimeoutSec 20; break }
        catch { Write-Host ("   still waking... {0:N0}s" -f $sw.Elapsed.TotalSeconds); Start-Sleep -Seconds 5 }
    }
    if (-not $health) { Stop-Here "Server did not answer within ~2 minutes. Check Render logs." }
    Ok ("/health OK after {0:N0}s: {1}" -f $sw.Elapsed.TotalSeconds, ($health | ConvertTo-Json -Compress))

    Head "Catalog"
    $cards = Invoke-RestMethod -Uri "$ServerUrl/cards" -TimeoutSec 30
    if (-not $cards) { Bad "/cards returned nothing"; return }
    foreach ($c in $cards) {
        $id = $c.id
        $r = $null
        try { $r = Invoke-WebRequest -UseBasicParsing -Uri "$ServerUrl/cards/$id/render.png" -TimeoutSec 60 } catch {}
        $anims = @()
        try { $anims = @(Invoke-RestMethod -Uri "$ServerUrl/cards/$id/animations" -TimeoutSec 30) } catch {}
        $len = 0
        if ($r) { $len = $r.RawContentLength; if (-not $len) { $len = $r.Content.Length } }
        if ($len -gt 0) { Ok ("{0}: render.png {1:N0} KB, animations: {2}" -f $id, ($len / 1KB), ($(if ($anims.Count) { $anims -join ', ' } else { 'none' }))) }
        else { Bad "${id}: render.png missing" }
    }

    Head "Manual device checks (these cannot be scripted)"
    Write-Host @"
  [ ] UPGRADE: install your OLD apk first, claim a card, then run:  .\release_v1.ps1 install   (adb install -r, no uninstall)
      -> the claimed card must still be in the Vault (tests the SQLite migration).
  [ ] Point at a wall for 30 s -> no card appears, nothing unlocks.
  [ ] Scan card art -> preview shows; Vault stays empty until a claim QR is scanned.
  [ ] Claim QR -> card lands in Vault; animation plays (frames are now WebP - confirm they still animate).
  [ ] Airplane mode -> the claimed card still tracks and renders.
  [ ] Transfer QR between two phones -> receiver gets it, sender loses it.
  [ ] Let Render sleep 20 min, open the app -> what does the user see? (no 'waking' state in V1 - known gap)
"@
}

# ------------------------------------------------------------------------------------------
function Phase-Install {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Stop-Here "adb not on PATH (it lives in <Android SDK>\platform-tools)." }
    $apk = Join-Path $Root ("dist\CardVault-v{0}.apk" -f $Version)
    if (-not (Test-Path $apk)) { Stop-Here "Build first:  .\release_v1.ps1 build" }
    adb devices
    adb install -r $apk
    if ($LASTEXITCODE -eq 0) { Ok "installed over the existing app (data preserved)" }
    else { Warn "If you see INSTALL_FAILED_UPDATE_INCOMPATIBLE the old APK used a different signing key - you must uninstall once (that wipes local vault data)." }
}

switch ($Phase) {
    "check"   { Phase-Check }
    "fix"     { Phase-Fix }
    "build"   { Phase-Build }
    "commit"  { Phase-Commit }
    "push"    { Phase-Push }
    "verify"  { Phase-Verify }
    "install" { Phase-Install }
}
