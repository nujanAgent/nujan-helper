# Privacy

Last updated: 30 October 2026

NuJan Helper has no account, no server of its own and no analytics or crash reporting. Its developer receives
nothing from it.

## What stays on your phone

Your workspaces and their files, the Linux system and everything installed in it, command output and logs, the list
of connected apps and what each may do, and the Helper's settings. They are kept in the Helper's own storage and
removed with the app.

## What leaves your phone, and when

Only when you set something up or run something:

- **Setting up the Linux system** downloads Debian from Docker Hub and GitHub. **Installing packages and toolchains**
  downloads from Debian's servers (`deb.debian.org`), `nodejs.org` and the registries your commands use. Those
  services see your IP address and what is downloaded, under their own privacy policies.
- **Commands** you run, or that a connected app runs with your permission, can reach any address they name. With the
  Helper's network switch off, every tool that could go online is refused, commands among them, since Android gives
  no way to keep a command offline.
- **USB devices** you plug in and allow are read from and written to. Nothing about them is sent anywhere.
- **Drive this phone**, only if you turn it on: what is on your screen, and the list of your apps, go to the connected app you allowed, and from
  there to the AI model it uses. That can include anything you have open. Passwords typed into password fields are not read. The Helper
  keeps a log of each call on the phone; it goes nowhere. The Hands add-on has no internet access of its own.

## Connected apps

An app such as NuJan connects to the Helper only after you allow it on the Helper's own screen, and it can use only
the groups of tools you turn on for it. Calls between the two apps stay on the phone. What the connected app does with
the results is covered by that app's privacy policy: NuJan, for example, may send a tool's result to the AI model you
chose in NuJan. You can see, change or end each connection under Connected apps.

## Permissions

| Permission | Why |
| --- | --- |
| Internet, network state | Downloads above, and the network access of the commands you run |
| Foreground service, wake lock | Keeps jobs and servers running while the Helper is in the background |
| Notifications | Shows running work and questions from connected apps |
| Install apps | Offers an APK a job built to Android's own install screen, where you decide; installs the Hands add-on when you ask |
| Accessibility (Hands add-on only) | Reads the screen and acts on it for Drive this phone, once you turn it on in Android's settings |
| USB host (optional) | Talks to boards and serial devices you plug in |

## Reporting a problem

Issues on GitHub are public. Do not post personal data, keys or logs that hold them. For a security problem, see
[SECURITY.md](SECURITY.md).

Questions about this policy: open an issue at https://github.com/nujanAgent/nujan-helper/issues.
