# build-apk.ps1 - 构建 Ubuntu 容器管理 APK（不依赖 gradle）
# aapt2 不支持中文路径，故在 ASCII 临时目录里编译，产物再拷回 dist
$ErrorActionPreference = "Stop"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt  = Join-Path $sdk "build-tools\36.0.0"
$androidJar = Join-Path $sdk "platforms\android-36\android.jar"
$src = "D:\360data\重要数据\我的文档\Trae-CN\temp_20261105_01\build\apk"
$out = Join-Path $src "dist"
$proj = Join-Path $env:TEMP "ubapk"

New-Item -ItemType Directory -Force $out | Out-Null
if (Test-Path $proj) { Remove-Item $proj -Recurse -Force }
New-Item -ItemType Directory -Force "$proj\classes" | Out-Null
New-Item -ItemType Directory -Force "$proj\dex" | Out-Null
Copy-Item "$src\src" $proj -Recurse
Copy-Item "$src\res" $proj -Recurse
Copy-Item "$src\AndroidManifest.xml" $proj

Write-Host "[1/6] javac"
$srcs = Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 11 -encoding UTF-8 -cp $androidJar -d "$proj\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[2/6] d8"
$cls = Get-ChildItem "$proj\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --min-api 26 --lib $androidJar --output "$proj\dex" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[3/6] aapt2 compile/link"
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$proj\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$proj\base.apk" -I $androidJar --manifest "$proj\AndroidManifest.xml" -R "$proj\res.zip" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[4/6] add classes.dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = "$proj\unsigned.apk"
Copy-Item "$proj\base.apk" $tmp -Force
$zip = [System.IO.Compression.ZipFile]::Open($tmp, 'Update')
$dex = "$proj\dex\classes.dex"
if (-not (Test-Path $dex)) { $zip.Dispose(); throw "classes.dex missing" }
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, "classes.dex", [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
$zip.Dispose()

Write-Host "[5/6] zipalign"
& "$bt\zipalign.exe" -f 4 $tmp "$proj\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] apksigner"
$ks = "$src\trae-release.jks"
if (-not (Test-Path $ks)) {
  & keytool -genkeypair -v -keystore $ks -alias trae -keyalg RSA -keysize 2048 -validity 10950 -storepass trae123456 -keypass trae123456 -dname "CN=Trae Ubuntu Container, OU=Dev, O=Trae, L=CN, S=CN, C=CN" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$final = Join-Path $out "Ubuntu-Container-v1.3.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:trae123456 --key-pass pass:trae123456 --out $final "$proj\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& "$bt\apksigner.bat" verify --print-certs $final | Select-Object -First 5

Write-Host "OK -> $final"
(Get-Item $final).Length
