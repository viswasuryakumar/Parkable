# Account Setup Guide (U1, U2, U3)

> Follow in order. **Golden rule for everything here: passwords, connection strings, and API
> keys never go into this repo, into PROGRESS.md, or into any file an agent reads.** Keep
> them in a personal notes app or password manager.

---

## U1 — Supabase (the database) · free · ~10 minutes

Supabase hosts our Postgres + PostGIS database. Free tier is plenty (500 MB DB).

1. Go to **https://supabase.com** → **Start your project**.
2. Sign up — easiest with your Google account, or plain email + password.
3. It asks you to create an **organization** → name it anything (e.g. `viswa-projects`),
   plan = **Free**.
4. **New project**:
   - Name: `parkable`
   - **Database password**: click *Generate a password* → **COPY IT NOW into your notes**
     (it is shown only once; you'll need it inside the connection string).
   - Region: **West US (North California)** — closest to the SF data our app targets.
   - Click **Create new project**, wait ~2 minutes while it provisions.
5. **Enable PostGIS**: left sidebar → **Database** → **Extensions** → search `postgis`
   → toggle it **on** (schema `extensions` default is fine).
6. **Create our table**: left sidebar → **SQL Editor** → **New query** → open
   `backend/sql/schema.sql` from this repo, copy ALL of it, paste, press **Run**.
   - Expected: "Success. No rows returned". 
   - Verify: sidebar → **Table Editor** → you should see a `rules` table.
7. **Get the connection string**: gear icon (**Project Settings**) → **Database** →
   **Connection string** section → choose **URI** and the **Transaction pooler** variant
   (port **6543** — this is the serverless-friendly one from PHASE-2-STUDY.md §3).
   It looks like:
   `postgresql://postgres.abcdefgh:[YOUR-PASSWORD]@aws-0-us-west-1.pooler.supabase.com:6543/postgres`
   - Replace `[YOUR-PASSWORD]` with the password from step 4.
   - **Save the full string in your notes.** Later it goes into AWS SSM, never into git.

✅ Done when: `rules` table visible, pooled connection string saved.

---

## U2 — AWS (the cloud) · free tier · ~30–60 minutes

⚠️ Requires a credit/debit card even for the free tier (identity verification; a small
temporary hold may appear). Our usage — Lambda, API Gateway, S3, SSM — sits comfortably
inside the free tier.

### 2a. Create the account
1. Go to **https://aws.amazon.com** → **Create an AWS Account**.
2. Root email = your personal email; account name e.g. `viswa-parkable`.
3. Verify the email code, set a strong **root password** (notes app!).
4. Choose **Personal** account type, fill address.
5. Add the payment card.
6. Phone verification (SMS code).
7. Support plan: **Basic (free)**.
8. Sign in to the **AWS Console** (console.aws.amazon.com).

### 2b. Two security steps (do these before anything else)
1. **MFA on root**: search bar → `IAM` → *Add MFA for root user* → pick **Authenticator
   app** (Google Authenticator / Microsoft Authenticator on your phone) → scan → done.
   The root account can spend money; protect it.
2. **Daily-use IAM user** (never use root day-to-day):
   - IAM → **Users** → **Create user** → name `parkable-dev`
   - ✔ Provide user access to the AWS Management Console → *I want to create an IAM user*
     → set a password.
   - Permissions: **Attach policies directly** → check `AdministratorAccess`
     (fine for a personal learning account; real companies scope this down).
   - Create. Note the **console sign-in URL** it shows (has your 12-digit account ID).
   - Then open the user → **Security credentials** tab → **Create access key** →
     use case: *Command Line Interface (CLI)* → create → **copy both the Access key ID
     and Secret access key into your notes** (secret shown once).

### 2c. Pick our region
Top-right of the console: set region to **us-west-1 (N. California)** — matches Supabase.
Always check you're in this region; resources are per-region and "where did my Lambda go?"
is usually a wrong-region problem.

### 2d. Tools on the laptop — with the no-admin-rights reality
Your machine is domain-managed, so installers may be blocked. Try in this order:

| Tool | Try | If blocked (no admin) |
|------|-----|------------------------|
| AWS CLI v2 | MSI from `aws.amazon.com/cli` | Use **AWS CloudShell** — a terminal in the browser (icon `>_` in the console toolbar) with AWS CLI preinstalled and already authenticated. Zero install. |
| SAM CLI | MSI from AWS docs ("Install SAM CLI") | Same: CloudShell can run SAM builds/deploys; or we deploy from the CLI-less path and rely on our 146 unit tests instead of local emulation. |
| Docker Desktop | Only needed for `sam local` (local API emulation) | **Almost certainly needs admin — skip it.** We test with unit tests locally and smoke-test against the real deployed (free-tier) API instead. |

If you do get the AWS CLI installed, configure it in PowerShell:
```powershell
aws configure
# AWS Access Key ID:  <from step 2b>
# AWS Secret Access Key: <from step 2b>
# Default region name: us-west-1
# Default output format: json
```
Tell Claude Code which of the three tools installed successfully — the deploy plan (C10)
adapts to whatever is available.

✅ Done when: you can sign in as `parkable-dev`, region us-west-1, and either the CLI works
in PowerShell (`aws sts get-caller-identity` prints your account) or CloudShell opens.

---

## U3 — Anthropic API key (live sign-reading) · paid · ~10 minutes · DO THIS LAST

Only needed when we want real photos scanned by Claude. Everything else runs free on the
offline fixture extractor.

1. Go to **https://console.anthropic.com** → sign up (Google or email).
2. **Billing**: add a card and buy the minimum credit pack ($5). At our ~3–6¢/scan
   estimate, $5 ≈ 100+ scans — plenty for development and the Phase 4 eval set.
3. Optional but smart: set a **monthly spend limit** ($5–10) in billing settings.
4. **API keys** → **Create key** → name `parkable-dev` → **copy it now** (shown once) →
   notes app.
5. Where it goes:
   - Locally: `$env:ANTHROPIC_API_KEY = "sk-ant-..."` in PowerShell before running the CLI
     with `--extractor=claude`.
   - In the cloud: stored in AWS SSM Parameter Store as a SecureString (task C9 wires
     this) — never in code, never in git.

✅ Done when: key saved in your notes, spend limit set.

---

## Bonus (for Phase 3, not urgent) — Node.js without admin rights
Copilot's mobile app needs Node. The portable ZIP works without admin:
download the **Windows x64 ZIP** from nodejs.org → extract to `C:\Users\<you>\tools\node`
→ add that folder to your user PATH (same pattern as our portable JDK). Ask Claude Code to
set it up when you're ready for Phase 3.

---

## After you finish U1/U2: tell Claude Code
Say something like *"U1 and U2 done — CLI installed"* (or *"installers blocked, CloudShell
only"*). It will update the board and run the deployment path that matches your setup.
**Paste the Supabase string / AWS keys / Anthropic key into your notes only — never into
the chat or repo.**
