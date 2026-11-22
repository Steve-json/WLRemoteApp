# WLRemote (Windows RDP 3389 + Built-in Pure-Java Linux SSH2 + Saved Profiles + Rounded Icon)
$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Test-ValidFreeRdpZip {
    param([string]$ZipPath)
    if (!(Test-Path $ZipPath)) { return $false }
    $fileInfo = Get-Item $ZipPath -ErrorAction SilentlyContinue
    if ($null -eq $fileInfo -or $fileInfo.Length -lt 5000000) { return $false }
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
        $hasSo = $false
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match "^lib/(arm64-v8a|armeabi-v7a)/libfreerdp-android\.so$") {
                $hasSo = $true
                break
            }
        }
        return $hasSo
    } catch {
        return $false
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
    }
}

function Extract-NativeLibsFromZip {
    param([string]$ZipPath, [string]$DestLibDir)
    if (Test-Path $DestLibDir) {
        Remove-Item $DestLibDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Force -Path $DestLibDir | Out-Null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match "^lib/(arm64-v8a|armeabi-v7a)/[^/]+\.so$") {
                $relPath = $entry.FullName.Substring(4)
                $outPath = Join-Path $DestLibDir ($relPath.Replace('/', '\'))
                $outParent = Split-Path -Parent $outPath
                if (!(Test-Path $outParent)) {
                    New-Item -ItemType Directory -Force -Path $outParent | Out-Null
                }
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $outPath, $true)
            }
        }
    } finally {
        $archive.Dispose()
    }
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  WLRemote - Windows (RDP) & Linux (SSH2) 双协议远程桌面一键构建器" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host ""

$TargetDir = "D:\"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

try {
    # ------------------------------------------------------------------
    # Step 1: Sync source, rounded icon & prebuilt bytecode to D:\
    # ------------------------------------------------------------------
    Write-Host "[1/5] 正在同步 WLRemote 工程源码与内置 SSH2 引擎至 $TargetDir ..." -ForegroundColor Yellow
    if (!(Test-Path $TargetDir)) {
        New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    }

    $normSrc = [System.IO.Path]::GetFullPath($ScriptDir).TrimEnd('\')
    $normDst = [System.IO.Path]::GetFullPath($TargetDir).TrimEnd('\')
    if ($normSrc -ne $normDst) {
        foreach ($sub in @("app", "prebuilt", "build")) {
            $p = Join-Path $TargetDir $sub
            if (Test-Path $p) {
                Remove-Item -Recurse -Force $p -ErrorAction SilentlyContinue
            }
        }
        Copy-Item -Path (Join-Path $ScriptDir "app") -Destination (Join-Path $TargetDir "app") -Recurse -Force
        Copy-Item -Path (Join-Path $ScriptDir "prebuilt") -Destination (Join-Path $TargetDir "prebuilt") -Recurse -Force
        foreach ($f in @("build.gradle", "settings.gradle", "gradle.properties", "README.md", "deploy_and_build.bat", "deploy_and_build.ps1")) {
            $sf = Join-Path $ScriptDir $f
            if (Test-Path $sf) {
                Copy-Item -Path $sf -Destination (Join-Path $TargetDir $f) -Force
            }
        }
    }
    Write-Host "  -> 源码与内置 SSH2 字节码同步完成: $TargetDir" -ForegroundColor Green

    # ------------------------------------------------------------------
    # Step 2: Check / Reuse Portable JDK 17 & Android SDK in .toolchain
    # ------------------------------------------------------------------
    $ToolchainDir = Join-Path $TargetDir ".toolchain"
    $JdkDir       = Join-Path $ToolchainDir "jdk"
    $SdkDir       = Join-Path $ToolchainDir "android-sdk"
    $FreeRdpDir   = Join-Path $ToolchainDir "freerdp-libs"
    New-Item -ItemType Directory -Force -Path $ToolchainDir, $JdkDir, $SdkDir, $FreeRdpDir | Out-Null

    Write-Host "[2/5] 检查便携版 OpenJDK 17 与 Android SDK 构建工具 ..." -ForegroundColor Yellow
    $JavaExe = Get-ChildItem -Path $JdkDir -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $JavaExe) {
        $JdkZip = Join-Path $ToolchainDir "jdk17.zip"
        & curl.exe -L --retry 3 -o $JdkZip "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip"
        Expand-Archive -Path $JdkZip -DestinationPath $JdkDir -Force
        Remove-Item $JdkZip -Force -ErrorAction SilentlyContinue
        $JavaExe = Get-ChildItem -Path $JdkDir -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    Write-Host "  -> 已就绪 Java: $($JavaExe.FullName)" -ForegroundColor Green

    $Aapt2Exe     = Get-ChildItem -Path $SdkDir -Filter "aapt2.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $ZipAlignExe  = Get-ChildItem -Path $SdkDir -Filter "zipalign.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $D8Jar        = Get-ChildItem -Path $SdkDir -Filter "d8.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $ApkSignerJar = Get-ChildItem -Path $SdkDir -Filter "apksigner.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $AndroidJar   = Get-ChildItem -Path $SdkDir -Filter "android.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1

    if ($null -eq $Aapt2Exe -or $null -eq $D8Jar) {
        $BtZip = Join-Path $ToolchainDir "build-tools.zip"
        & curl.exe -L --retry 3 -o $BtZip "https://dl.google.com/android/repository/build-tools_r30.0.2-windows.zip"
        Expand-Archive -Path $BtZip -DestinationPath $SdkDir -Force
        Remove-Item $BtZip -Force -ErrorAction SilentlyContinue
        $Aapt2Exe     = Get-ChildItem -Path $SdkDir -Filter "aapt2.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        $ZipAlignExe  = Get-ChildItem -Path $SdkDir -Filter "zipalign.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        $D8Jar        = Get-ChildItem -Path $SdkDir -Filter "d8.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        $ApkSignerJar = Get-ChildItem -Path $SdkDir -Filter "apksigner.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($null -eq $AndroidJar) {
        $PlatZip = Join-Path $ToolchainDir "platform-30.zip"
        & curl.exe -L --retry 3 -o $PlatZip "https://dl.google.com/android/repository/platform-30_r03.zip"
        Expand-Archive -Path $PlatZip -DestinationPath $SdkDir -Force
        Remove-Item $PlatZip -Force -ErrorAction SilentlyContinue
        $AndroidJar = Get-ChildItem -Path $SdkDir -Filter "android.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    Write-Host "  -> 已就绪 Android SDK Build-Tools & Platform 30" -ForegroundColor Green

    # ------------------------------------------------------------------
    # Step 3: Check / Reuse FreeRDP Native (.so) Engine (SSH2 is built-in!)
    # ------------------------------------------------------------------
    Write-Host "[3/5] 检查 FreeRDP 原生动态库 (Linux SSH2 引擎已直接内置于 classes.jar)..." -ForegroundColor Yellow
    $ExtractedLibDir = Join-Path $FreeRdpDir "lib"
    $CheckSo = Get-ChildItem -Path $ExtractedLibDir -Filter "libfreerdp-android.so" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1

    if ($null -eq $CheckSo) {
        $FreeRdpApkZip = Join-Path $FreeRdpDir "afreerdp.zip"
        if (Test-Path $FreeRdpApkZip) {
            if (Test-ValidFreeRdpZip $FreeRdpApkZip) {
                Extract-NativeLibsFromZip -ZipPath $FreeRdpApkZip -DestLibDir $ExtractedLibDir
                $CheckSo = Get-ChildItem -Path $ExtractedLibDir -Filter "libfreerdp-android.so" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
            } else {
                Remove-Item $FreeRdpApkZip -Force -ErrorAction SilentlyContinue
            }
        }
    }

    if ($null -eq $CheckSo) {
        $FreeRdpApkZip = Join-Path $FreeRdpDir "afreerdp.zip"
        $CandidateUrls = @(
            "https://mirrors.tuna.tsinghua.edu.cn/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://mirrors.ustc.edu.cn/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://mirrors.bfsu.edu.cn/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://mirrors.nju.edu.cn/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://mirrors.sjtug.sjtu.edu.cn/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://mirrors.aliyun.com/fdroid/archive/com.freerdp.afreerdp_3005001.apk",
            "https://f-droid.org/archive/com.freerdp.afreerdp_3005001.apk"
        )
        foreach ($url in ($CandidateUrls | Select-Object -Unique)) {
            & curl.exe -s -I -L -f --connect-timeout 4 --max-time 8 $url >$null 2>$null
            if ($LASTEXITCODE -ne 0) { continue }
            Write-Host "  -> 正在下载 FreeRDP 原生引擎包: $url" -ForegroundColor Cyan
            if (Test-Path $FreeRdpApkZip) { Remove-Item $FreeRdpApkZip -Force -ErrorAction SilentlyContinue }
            & curl.exe -L -f --progress-bar --connect-timeout 10 --max-time 600 --retry 3 -o $FreeRdpApkZip $url
            if ($LASTEXITCODE -eq 0 -and (Test-ValidFreeRdpZip $FreeRdpApkZip)) {
                Extract-NativeLibsFromZip -ZipPath $FreeRdpApkZip -DestLibDir $ExtractedLibDir
                $CheckSo = Get-ChildItem -Path $ExtractedLibDir -Filter "libfreerdp-android.so" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($null -ne $CheckSo) { break }
            }
        }
    } else {
        Write-Host "  -> 已复用本地 FreeRDP 原生动态库与内置 PureSsh2Client 引擎" -ForegroundColor Green
    }

    if ($null -eq $CheckSo) {
        throw "未能就绪 FreeRDP 原生动态库，请检查网络连接后重试。"
    }

    # ------------------------------------------------------------------
    # Step 4: Compile Rounded Icon + Link Manifest + Convert DEX (prebuilt/classes.jar ONLY) + Sign APK
    # ------------------------------------------------------------------
    Write-Host "[4/5] 正在打包 WLRemote 安卓安装包 (APK) ..." -ForegroundColor Yellow
    $BuildDir = Join-Path $TargetDir "build"
    $ApkOutDir = Join-Path $TargetDir "apk"
    if (Test-Path $BuildDir) { Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path $BuildDir, $ApkOutDir | Out-Null

    $ClassesJarToUse = Join-Path $TargetDir "prebuilt\classes.jar"

    # 4.1 Compile rounded-corner icon resource & link AndroidManifest.xml
    $ManifestFile = Join-Path $TargetDir "app\src\main\AndroidManifest.xml"
    $IconFile     = Join-Path $TargetDir "app\src\main\res\drawable\ic_launcher.png"
    $ResFlatDir   = Join-Path $BuildDir "res_flat"
    New-Item -ItemType Directory -Force -Path $ResFlatDir | Out-Null

    $UnalignedApk = Join-Path $BuildDir "unaligned.apk"
    if (Test-Path $IconFile) {
        & $Aapt2Exe.FullName compile $IconFile -o $ResFlatDir
        $FlatIcon = Get-ChildItem -Path $ResFlatDir -Filter "*.flat" | Select-Object -First 1
        if ($null -ne $FlatIcon) {
            & $Aapt2Exe.FullName link -o $UnalignedApk --manifest $ManifestFile -I $AndroidJar.FullName $FlatIcon.FullName
        }
    }
    if (!(Test-Path $UnalignedApk)) {
        throw "aapt2 link 失败，未能生成基础 APK 容器。"
    }
    Write-Host "  -> [4.1] WLRemote 清单链接完成" -ForegroundColor Green

    # 4.2 Convert prebuilt/classes.jar (100% pure Java 8, zero external JARs) to classes.dex via D8
    & $JavaExe.FullName -cp $D8Jar.FullName com.android.tools.r8.D8 --min-api 21 --lib $AndroidJar.FullName --output $BuildDir $ClassesJarToUse
    $ClassesDex = Join-Path $BuildDir "classes.dex"
    if (!(Test-Path $ClassesDex)) {
        throw "D8 字节码转换失败，未能生成 classes.dex。"
    }
    Write-Host "  -> [4.2] DEX 字节码转换完成 (classes.dex)" -ForegroundColor Green

    # 4.3 Inject classes.dex AND lib/<abi>/*.so into unaligned.apk
    $ZipArchive = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $ExistingDex = $ZipArchive.GetEntry("classes.dex")
        if ($null -ne $ExistingDex) { $ExistingDex.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $ZipArchive, $ClassesDex, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null

        foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
            $AbiDir = Join-Path $ExtractedLibDir $abi
            if (Test-Path $AbiDir) {
                $AbiSoFiles = Get-ChildItem -Path $AbiDir -Filter "*.so"
                foreach ($so in $AbiSoFiles) {
                    $EntryName = "lib/$abi/$($so.Name)"
                    $ExistingEntry = $ZipArchive.GetEntry($EntryName)
                    if ($null -ne $ExistingEntry) { $ExistingEntry.Delete() }
                    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                        $ZipArchive, $so.FullName, $EntryName, [System.IO.Compression.CompressionLevel]::Optimal
                    ) | Out-Null
                }
            }
        }
    } finally {
        $ZipArchive.Dispose()
    }
    Write-Host "  -> [4.3] 已将 DEX 与 FreeRDP 原生动态库封装进 APK" -ForegroundColor Green

    # 4.4 Zipalign (4-byte alignment)
    $AlignedApk = Join-Path $BuildDir "aligned.apk"
    & $ZipAlignExe.FullName -f -p 4 $UnalignedApk $AlignedApk
    if (!(Test-Path $AlignedApk)) {
        Copy-Item $UnalignedApk $AlignedApk -Force
    }
    Write-Host "  -> [4.4] Zipalign 字节对齐完成" -ForegroundColor Green

    # 4.5 Sign APK with debug.keystore -> WLRemote.apk
    $KeystoreFile = Join-Path $TargetDir "prebuilt\debug.keystore"
    $FinalApk     = Join-Path $ApkOutDir "WLRemote.apk"
    & $JavaExe.FullName -jar $ApkSignerJar.FullName sign `
        --ks $KeystoreFile `
        --ks-pass pass:android `
        --key-pass pass:android `
        --ks-key-alias androiddebugkey `
        --out $FinalApk `
        $AlignedApk

    if (!(Test-Path $FinalApk)) {
        throw "apksigner 签名失败，未能生成最终 APK。"
    }

    $ApkSizeMB = [math]::Round((Get-Item $FinalApk).Length / 1MB, 2)
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "  [5/5] 编译成功！WLRemote (Windows RDP + Linux SSH) APK 已生成：" -ForegroundColor Green
    Write-Host "  应用名称: WLRemote" -ForegroundColor Cyan
    Write-Host "  安装文件: $FinalApk ($ApkSizeMB MB)" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host ""
    Start-Process explorer.exe -ArgumentList "/select,`"$FinalApk`""
} catch {
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Red
    Write-Host "  构建过程中发生错误: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "======================================================================" -ForegroundColor Red
}
