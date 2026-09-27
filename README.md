# ResumeJobHunter

A pure-Java resume matching automation tool. Drop your resume in a folder,
run one command, and get a self-contained HTML dashboard of live, scored,
apply-ready job matches.

## What it does

- Reads your resume (PDF or DOCX), extracts your name, email, phone,
  skills, and target role — no external NLP service, just PDFBox/regex.
- Pulls live job listings from 8 free, no-signup APIs (Remotive, RemoteOK,
  Arbeitnow, Jobicy, Himalayas, The Muse, Greenhouse, Lever), scores each
  one against your resume, and generates deep-link "Quick Apply" buttons
  into LinkedIn, Naukri, Shine, and Indeed.
- Builds one self-contained `dashboard.html` per run — inline CSS/JS, no
  server, no CDN — complete with a ready cover letter, short pitch, and
  quick answers for every matched job, and opens it in your browser.

## Requirements

- **Java 17+** (JDK, not just JRE)
- **Maven** (3.8+) on your PATH
- **Google Chrome** — only needed if you use `--assist` (assisted fill on
  LinkedIn/Naukri). Not required for the normal dashboard flow.

## Build

```
mvn clean package
```

or just double-click `run.bat` on Windows — it builds automatically on
first run.

## How to run

**Windows (CMD):**
```
run.bat
```

**Linux / Mac:**
```
chmod +x run.sh
./run.sh
```

That's it — one command. It reads your resume, fetches and scores live
jobs, builds the dashboard, and opens it in your default browser.

### Optional flags

```
run.bat --auto-apply "https://boards.greenhouse.io/postman/jobs/12345" --company "Postman" --title "Junior Backend Engineer"
run.bat --auto-apply "https://jobs.lever.co/cred/abcd" --live
run.bat --assist "https://www.naukri.com/job-listings-xyz" --company "XYZ" --title "Java Developer"
```

- `--auto-apply <url>` — Greenhouse/Lever only. Without `--live` it's a
  **dry run**: it prints exactly what would be sent (fields, resume file,
  cover letter preview) and sends nothing. Add `--live` to actually submit.
- `--assist <url>` — LinkedIn/Naukri. Opens a real Chrome window, fills in
  what it can detect, and **stops before Submit** — you review and click
  Submit yourself.

## How to add a resume

Drop a `.pdf` or `.docx` file into `input_resumes/`. Multiple resumes are
each processed in turn (one dashboard per resume, per run).

## Where output goes

Every run writes to `output/<today's date>/<run number>/dashboard.html`.
Run numbers only ever increase for a given day — nothing is overwritten,
so you keep a full history of every run. The dashboard is auto-opened in
your default browser when the run finishes (skipped silently if the
environment is headless).

A running `applied_jobs.csv` in the project root tracks every job you've
been shown, so future runs can tell "new since last time" jobs apart from
ones you've already seen.

## Why LinkedIn/Naukri/Shine/Indeed aren't fully automated

These platforms actively detect and permanently ban accounts that submit
applications via automation — this is a real, actively-enforced anti-fraud
system, not a workaround-able quirk. Scraping their listings or
auto-submitting applications risks your account. So instead of scraping,
ResumeJobHunter builds pre-filtered **deep-link search URLs** you open and
apply from yourself (one click on their site), and for LinkedIn/Naukri
job pages the assisted-fill tier stops before the Submit button so a human
is always the one who submits.

## Why pure Java

One language end to end, no cross-language bridge, no Python or Node
runtime to install. The HTML dashboard itself is built with Java 17 text
blocks (`"""`) and `StringBuilder`, with every dynamic string passed
through `HtmlEscape.escape()` — no template engine required.

## How to add more Greenhouse/Lever companies

1. Open the company's careers page.
2. Check the URL:
   - Greenhouse: `boards.greenhouse.io/<slug>` → the slug is what
     `JobFetcher` needs (add it to `GREENHOUSE_SLUGS` in
     `JobFetcher.java`).
   - Lever: `jobs.lever.co/<slug>` → add it to `LEVER_SLUGS`.
3. Rebuild (`mvn clean package`) and re-run.

## Troubleshooting

- **PDFBox font warnings in the console** — harmless; PDFBox logs these
  for resumes with unusual embedded fonts. Text extraction still works.
- **All sources failed / very few jobs** — usually a network or geo-block
  issue; each source fails independently and is skipped, so check your
  internet connection and try again. Corporate proxies sometimes block
  one or more of the free APIs.
- **Empty results (0 matched jobs)** — your resume may need more
  recognizable skill keywords; check `src/main/resources/skills.txt` and
  make sure your resume's skills section uses common spellings.
- **Browser didn't auto-open** — the dashboard path is printed in the
  console; open `output/<date>/<run>/dashboard.html` manually. Auto-open
  is skipped silently on headless/server environments.

## Legal / ethical note

This tool is intended for **personal job-search use only**. It never
scrapes or auto-submits on LinkedIn, Naukri, Shine, or Indeed, and its
Greenhouse/Lever auto-apply tier defaults to a dry run. Please respect
each site's Terms of Service and use this responsibly for your own job
search.
