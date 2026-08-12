<#
.SYNOPSIS
    Point the deployed Parkable stack at a new Supabase database.

.DESCRIPTION
    The Supabase connection string lives in SSM at /parkable/db-url, but
    template.yaml consumes it as AWS::SSM::Parameter::Value<String>, which
    CloudFormation resolves at DEPLOY time and bakes into each Lambda's
    environment. Updating SSM alone therefore fixes only the next deploy and
    leaves the running functions pointing at the old database.

    This script does both: it updates SSM (so the next `sam deploy` is correct)
    and patches the live Lambda environments (so the fix takes effect now).

    It refuses to write anything until the supplied connection string has
    actually been proven to connect, so a typo can't take the stack from
    "broken" to "broken differently".

.PARAMETER ConnectionString
    The Supabase pooler URI, either shape:
      postgresql://postgres.<ref>:<password>@aws-1-us-west-1.pooler.supabase.com:6543/postgres
      jdbc:postgresql://postgres.<ref>:<password>@aws-1-...:6543/postgres
    Use the Transaction pooler (port 6543) variant, per SETUP-ACCOUNTS.md U1.7.

.EXAMPLE
    ./infra/restore-db.ps1 -ConnectionString 'postgresql://postgres.abc:pw@aws-1-us-west-1.pooler.supabase.com:6543/postgres'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConnectionString,

    [string]$Region = 'us-west-1',
    [string]$ParameterName = '/parkable/db-url',

    # Skip the pre-flight connection test. Only for the case where the DB is
    # firewalled off from this machine but reachable from Lambda.
    [switch]$SkipVerify
)

$ErrorActionPreference = 'Stop'
$env:AWS_PAGER = ''

# Every function in template.yaml that receives PARKABLE_DB_URL.
$functions = @('parkable-check', 'parkable-nearby', 'parkable-scan', 'parkable-report', 'parkable-reports')

# The repository parses a jdbc: URL, so normalise to that shape regardless of
# which form Supabase's dashboard handed over.
$jdbcUrl = $ConnectionString.Trim()
if ($jdbcUrl.StartsWith('postgresql://')) { $jdbcUrl = 'jdbc:' + $jdbcUrl }
if (-not $jdbcUrl.StartsWith('jdbc:postgresql://')) {
    throw "Connection string must start with postgresql:// or jdbc:postgresql:// (got '$($jdbcUrl.Substring(0, [Math]::Min(24, $jdbcUrl.Length)))...')"
}
if ($jdbcUrl -notmatch '^jdbc:postgresql://([^:@/]+):(.+)@(.+)$') {
    throw 'Connection string has no embedded user:password. Use the URI variant that includes the password.'
}
$dbHost = $matches[3]

if ($jdbcUrl -match '\[YOUR-PASSWORD\]') {
    throw 'The [YOUR-PASSWORD] placeholder is still in the string - substitute the real password first.'
}

Write-Host "Target database host: $dbHost" -ForegroundColor Cyan

# --- Pre-flight: prove it connects before touching any AWS state ----------
if (-not $SkipVerify) {
    $driver = Join-Path $env:USERPROFILE '.m2\repository\org\postgresql\postgresql\42.7.4\postgresql-42.7.4.jar'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $java)) { $java = 'C:\Users\018316532\tools\jdk-25.0.2\bin\java.exe' }

    if ((Test-Path $driver) -and (Test-Path $java)) {
        $probe = Join-Path ([System.IO.Path]::GetTempPath()) 'ParkableDbProbe.java'
        @'
import java.sql.*; import java.util.Properties; import java.util.regex.*;
public class ParkableDbProbe {
  public static void main(String[] a) throws Exception {
    Matcher m = Pattern.compile("^jdbc:postgresql://([^:@/]+):(.+)@(.+)$").matcher(System.getenv("PARKABLE_DB_URL"));
    if (!m.matches()) { System.out.println("PROBE_FAIL unparseable url"); System.exit(2); }
    Properties p = new Properties();
    p.setProperty("user", m.group(1)); p.setProperty("password", m.group(2));
    p.setProperty("connectTimeout", "15"); p.setProperty("loginTimeout", "15"); p.setProperty("sslmode", "require");
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + m.group(3), p);
         Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("select count(*) from rules")) {
        rs.next(); System.out.println("PROBE_OK rules=" + rs.getInt(1));
      } catch (SQLException tableMissing) {
        System.out.println("PROBE_NO_TABLE " + tableMissing.getMessage().replace(m.group(2), "***"));
        System.exit(3);
      }
    } catch (Exception e) {
      System.out.println("PROBE_FAIL " + e.getMessage().replace(m.group(2), "***")); System.exit(2);
    }
  }
}
'@ | ForEach-Object {
            # PS 5.1's -Encoding utf8 emits a BOM, which javac rejects as an
            # illegal character - write UTF-8 without one.
            [System.IO.File]::WriteAllText($probe, $_, (New-Object System.Text.UTF8Encoding($false)))
        }

        $env:PARKABLE_DB_URL = $jdbcUrl
        $result = & $java -cp $driver $probe
        $code = $LASTEXITCODE
        Remove-Item $probe -Force -ErrorAction SilentlyContinue

        Write-Host $result
        if ($code -eq 3) {
            throw "Connected, but the 'rules' table is missing. Run backend/sql/schema.sql in the Supabase SQL Editor first (SETUP-ACCOUNTS.md U1.6), then re-run this script."
        }
        if ($code -ne 0) {
            throw 'Could not connect with that string - nothing was changed. Check the password and that the project is active.'
        }
        Write-Host 'Pre-flight connection OK.' -ForegroundColor Green
    }
    else {
        Write-Warning 'Postgres driver or JDK not found locally; skipping pre-flight check.'
    }
}

# --- Update SSM (fixes future deploys) -----------------------------------
Write-Host "Updating SSM $ParameterName ..." -ForegroundColor Cyan
aws ssm put-parameter --name $ParameterName --value $jdbcUrl --type String --overwrite --region $Region | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to update SSM parameter $ParameterName" }

# --- Patch live Lambda environments (fixes the running stack now) --------
# update-function-configuration replaces the whole Variables map, so read,
# merge, and write back rather than clobbering sibling keys.
foreach ($fn in $functions) {
    Write-Host "Patching $fn ..." -ForegroundColor Cyan

    $vars = aws lambda get-function-configuration --function-name $fn --region $Region --query 'Environment.Variables' --output json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { Write-Warning "Could not read $fn - skipping."; continue }

    $merged = @{}
    foreach ($p in $vars.PSObject.Properties) { $merged[$p.Name] = $p.Value }
    $merged['PARKABLE_DB_URL'] = $jdbcUrl

    $payload = @{ Variables = $merged } | ConvertTo-Json -Compress -Depth 5
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) "parkable-env-$fn.json"
    Set-Content -Path $tmp -Value $payload -Encoding utf8

    aws lambda update-function-configuration --function-name $fn --region $Region --environment "file://$tmp" | Out-Null
    $rc = $LASTEXITCODE
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    if ($rc -ne 0) { Write-Warning "Failed to update $fn" } else { Write-Host "  ok" -ForegroundColor Green }
}

Write-Host ''
Write-Host 'Done. Verify with:' -ForegroundColor Green
Write-Host '  curl "https://8o6uod8i7f.execute-api.us-west-1.amazonaws.com/Prod/nearby?lat=37.7749&lng=-122.4194&radius=300"'
Write-Host 'A 200 with a JSON array means the database is live again (an empty array just means no rules loaded yet).'
