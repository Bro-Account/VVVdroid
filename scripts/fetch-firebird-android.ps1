#Requires -Version 5.1
<#
.SYNOPSIS
    Downloads the official Firebird 5 embedded Android AAR into app\libs\ and
    patches it for use on Android shared storage.

.DESCRIPTION
    Fetches Firebird-5.0.4.1812-0-android-embedded.aar from the FirebirdSQL
    GitHub release and applies one binary patch to libfbclient.so (arm64-v8a
    and x86_64): the engine locks the database file via flock(), which
    Android's FUSE (shared storage) rejects with ENOSYS, while fcntl POSIX
    locks work there. The flock PLT stub is replaced by a "return 0" stub,
    i.e. the local database-file lock becomes a no-op. This is acceptable for
    a strictly read-only, single-attach viewer; cross-device locking is
    impossible anyway (file syncers replicate data, not locks). The patch is
    applied inside the AAR (that is what gets packaged into the APK) and to
    the libfbclient.so copies that CMake links against.

    The AAR and .so files are not stored in git; re-run this script after a
    fresh checkout. Safe to re-run (already-patched files are detected and
    skipped).

.PARAMETER Version
    Firebird AAR version string.

.EXAMPLE
    .\scripts\fetch-firebird-android.ps1
#>
[CmdletBinding()]
param(
    [string]$Version = '5.0.4.1812-0',
    [string]$SevenZip = "$env:ProgramFiles\7-Zip\7z.exe"
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$LibsDir  = Join-Path $RepoRoot 'app\libs'
$Name     = "Firebird-$Version-android-embedded.aar"
$Url      = "https://github.com/FirebirdSQL/firebird/releases/download/v5.0.4/$Name"

New-Item -ItemType Directory -Force -Path $LibsDir | Out-Null
$Target = Join-Path $LibsDir $Name

if (Test-Path $Target) {
    Write-Host "Cached: $Target"
} else {
    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Target -UseBasicParsing
    Write-Host ("Downloaded: {0} ({1:N1} MB)" -f $Target, ((Get-Item $Target).Length / 1MB))
}

# ---------------------------------------------------------------------------
# ELF64 patching: locate the flock PLT stub and neutralize it.
#
# PLT entry i corresponds to the i-th JUMP_SLOT relocation in .rela.plt; the
# first PLT entry (PLT0) is 32 bytes on arm64 and 16 bytes on x86_64,
# subsequent entries are 16 bytes on both.
#   arm64   stub: adrp/ldr/add/br  ->  movz w0,#0 ; ret        (8 bytes)
#   x86_64  stub: jmpq *GOT        ->  xor eax,eax ; ret       (3 bytes)
# ---------------------------------------------------------------------------
function Get-ElfSections([byte[]]$b) {
    $shoff     = [BitConverter]::ToUInt64($b, 0x28)
    $shentsize = [BitConverter]::ToUInt16($b, 0x3a)
    $shnum     = [BitConverter]::ToUInt16($b, 0x3c)
    $shstrndx  = [BitConverter]::ToUInt16($b, 0x3e)
    $strHdrOff = [int]($shoff + $shstrndx * $shentsize)
    $strData   = [BitConverter]::ToUInt64($b, $strHdrOff + 0x18)  # sh_offset
    $strSize   = [BitConverter]::ToUInt64($b, $strHdrOff + 0x20)  # sh_size
    $sections = @()
    for ($i = 0; $i -lt $shnum; $i++) {
        $off = [int]($shoff + $i * $shentsize)
        $nameOff = [BitConverter]::ToUInt32($b, $off)
        $end = $strData + $nameOff
        $nameLen = 0
        while ($b[$end + $nameLen] -ne 0) { $nameLen++ }
        $name = [System.Text.Encoding]::ASCII.GetString($b, [int]$end, $nameLen)
        $sections += [pscustomobject]@{
            Name   = $name
            Type   = [BitConverter]::ToUInt32($b, $off + 4)
            Addr   = [BitConverter]::ToUInt64($b, $off + 0x10)
            Offset = [BitConverter]::ToUInt64($b, $off + 0x18)
            Size   = [BitConverter]::ToUInt64($b, $off + 0x20)
        }
    }
    return $sections
}

function Get-DynstrString([byte[]]$b, [object]$strSec, [int]$nameOff) {
    $start = [int]($strSec.Offset + $nameOff)
    $len = 0
    while ($b[$start + $len] -ne 0) { $len++ }
    return [System.Text.Encoding]::ASCII.GetString($b, $start, $len)
}

function Patch-LibFlock([string]$Path) {
    $b = [System.IO.File]::ReadAllBytes($Path)
    $sec = Get-ElfSections $b

    $plt     = $sec | Where-Object { $_.Name -eq '.plt' }
    $relaPlt = $sec | Where-Object { $_.Name -eq '.rela.plt' }
    $dynsym  = $sec | Where-Object { $_.Name -eq '.dynsym' }
    $dynstr  = $sec | Where-Object { $_.Name -eq '.dynstr' }
    if (-not ($plt -and $relaPlt -and $dynsym -and $dynstr)) {
        Write-Host "Skipped (no PLT sections): $(Split-Path -Leaf $Path)"
        return
    }

    # find the flock relocation index in .rela.plt
    $flockIndex = -1
    $nReloc = [int]($relaPlt.Size / 24)
    for ($i = 0; $i -lt $nReloc; $i++) {
        $off = [int]($relaPlt.Offset + $i * 24)
        $rInfo = [BitConverter]::ToUInt64($b, $off + 8)
        $symIdx = [int]($rInfo -shr 32)
        $symOff = [int]($dynsym.Offset + $symIdx * 24)   # st_name is at +0
        $stName = [BitConverter]::ToUInt32($b, $symOff)
        $symName = Get-DynstrString $b $dynstr $stName
        if ($symName -eq 'flock') {
            $flockIndex = $i
            break
        }
    }
    if ($flockIndex -lt 0) {
        Write-Host "Skipped (no flock import): $(Split-Path -Leaf $Path)"
        return
    }

    $isArm64 = ([BitConverter]::ToUInt16($b, 0x12) -eq 183)   # e_machine = EM_AARCH64
    $stubOff = [int]($plt.Offset + ($(if ($isArm64) { 32 } else { 16 })) + $flockIndex * 16)
    if ($isArm64) {
        $patch = [byte[]](0x00,0x00,0x80,0x52, 0xc0,0x03,0x5f,0xd6)  # movz w0,#0; ret
        $expectedAdrp = ($b[$stubOff + 3] -band 0x9f) -eq 0x90        # adrp opcode mask
        if (-not $expectedAdrp) {
            if (($b[$stubOff] -eq 0x00) -and ($b[$stubOff + 3] -eq 0x52)) {
                Write-Host "Already patched: $Path"
                return
            }
            throw "Unexpected PLT stub bytes in $Path at 0x$('{0:x}' -f $stubOff)"
        }
    } else {
        $patch = [byte[]](0x31,0xc0,0xc3)                             # xor eax,eax; ret
        if (-not (($b[$stubOff] -eq 0xff) -and ($b[$stubOff + 1] -eq 0x25))) {
            if (($b[$stubOff] -eq 0x31) -and ($b[$stubOff + 1] -eq 0xc0)) {
                Write-Host "Already patched: $Path"
                return
            }
            throw "Unexpected PLT stub bytes in $Path at 0x$('{0:x}' -f $stubOff)"
        }
    }

    $fs = [System.IO.File]::Open($Path, 'Open', 'ReadWrite')
    $fs.Position = $stubOff
    $fs.Write($patch, 0, $patch.Length)
    $fs.Close()
    Write-Host ("Patched flock stub in {0} at file offset 0x{1:x}" -f $Path, $stubOff)
}

foreach ($abi in @('arm64-v8a', 'x86_64')) {
    $outDir = Join-Path $LibsDir "fbclient\$abi"
    $soFile = Join-Path $outDir 'libfbclient.so'
    $tmpDir = Join-Path $env:TEMP "fb-aar-patch-$abi"

    if (-not (Test-Path $SevenZip)) { throw "7-Zip not found at $SevenZip (pass -SevenZip <path>)" }

    # extract all libraries (we cannot rely on any earlier state)
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    tar -C $tmpDir -xf $Target "jni/$abi"
    $jniDir = Join-Path $tmpDir "jni\$abi"
    if (-not (Test-Path (Join-Path $jniDir 'libfbclient.so'))) {
        throw "Extraction of jni/$abi failed"
    }

    # patch every library that imports flock (the engine runs in
    # libEngine13.so, not only in libfbclient.so)
    $patched = @()
    Get-ChildItem $jniDir -Filter '*.so' | ForEach-Object {
        $relEntry = "jni/$abi/$($_.Name)"
        Patch-LibFlock $_.FullName
        Push-Location $tmpDir
        try {
            & $SevenZip a $Target $relEntry | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Updating $relEntry in $Target failed" }
        } finally {
            Pop-Location
        }
        $patched += $_.Name
    }

    # copy for CMake linking
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Copy-Item (Join-Path $jniDir 'libfbclient.so') $soFile -Force
    Remove-Item $tmpDir -Recurse -Force
    Write-Host "Updated: $soFile ($($patched.Count) libs patched/skipped-checked)"
}

Write-Host 'Done. Open the project in Android Studio or run gradlew.'
