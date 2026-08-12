$ErrorActionPreference = 'Stop'

function Update-AnchorsInFiles {
  param(
    [Parameter(Mandatory=$true)][string]$Glob,
    [Parameter(Mandatory=$true)][scriptblock]$ShouldUpdate,
    [Parameter(Mandatory=$true)][string]$NewHref
  )

  $files = Get-ChildItem -Path $Glob -File
  $changed = @()

  foreach ($file in $files) {
    $content = Get-Content -Path $file.FullName -Raw
    $original = $content

    $anchorPattern = '(?s)<a\b[^>]*href="#"[^>]*>.*?<\/a>'
    $matches = [regex]::Matches($content, $anchorPattern)

    if ($matches.Count -gt 0) {
      $sb = New-Object System.Text.StringBuilder
      $cursor = 0

      foreach ($m in $matches) {
        [void]$sb.Append($content.Substring($cursor, $m.Index - $cursor))

        $a = $m.Value
        if (& $ShouldUpdate $a) {
          $a = ($a -replace 'href="#"', ('href="' + $NewHref + '"'))
        }
        [void]$sb.Append($a)

        $cursor = $m.Index + $m.Length
      }

      [void]$sb.Append($content.Substring($cursor))
      $content = $sb.ToString()
    }

    if ($content -ne $original) {
      Set-Content -Path $file.FullName -Value $content -Encoding utf8
      $changed += $file.Name
    }
  }

  return $changed
}

$branchChanged = Update-AnchorsInFiles -Glob 'branch-portal/*.html' -NewHref 'home.html' -ShouldUpdate {
  param($a)
  ($a -match 'material-symbols-outlined') -and ($a -match '>\s*(home|grid_view|dashboard)\s*<') -and ($a -match '首頁')
}

$hqChanged = Update-AnchorsInFiles -Glob 'hq-portal/*.html*' -NewHref 'hq-dashboard.html' -ShouldUpdate {
  param($a)
  ($a -match '全局儀表板') -and ($a -match '>\s*dashboard\s*<')
}

Write-Host ("Branch updated: {0}" -f $branchChanged.Count)
$branchChanged | Sort-Object | ForEach-Object { Write-Host (" - {0}" -f $_) }
Write-Host ("HQ updated: {0}" -f $hqChanged.Count)
$hqChanged | Sort-Object | ForEach-Object { Write-Host (" - {0}" -f $_) }
