# Security

## Reporting a problem

Report it privately through GitHub: the repository's **Security** tab, then **Report a vulnerability**. Please do not
open a public issue for it. Say which version of the Helper and which phone and Android version you used, the steps
that show the problem, and what someone could do with it.

The report is answered there. A report is acknowledged within 7 days, and within 30 days it has a fix or a plan with a date. A fix is released before the details are made public.

## Versions

Only the latest release is fixed.

## What is in scope

- Connected apps: the consent screen, grants, and the check of which app is calling
- Workspaces: anything that reads or writes outside a workspace through the Helper's file tools
- Commands, jobs and servers, and the limits on them
- The USB bridge and the install path for APKs
- Drive this phone: Stop, the questions, the Helper's own screens being out of reach, the pairing key, and who may call the Hands add-on
- Any way another app on the phone can reach the Helper's tools without your consent

## What is not

- What a command you run does inside the Linux system
- Problems in Debian packages, PRoot or Termux's builds of it; report those to their projects
- A phone that is rooted or already compromised
- What a command in the phone shell group does once you have allowed it, for one command or for a while: it has the power of USB debugging, and the Helper's questions
  are a safeguard against mistakes, not a sandbox
