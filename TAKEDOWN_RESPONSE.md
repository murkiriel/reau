# Answering a takedown request

Português (pt-BR): [TAKEDOWN_RESPONSE.pt-br.md](TAKEDOWN_RESPONSE.pt-br.md) · Español: [TAKEDOWN_RESPONSE.es.md](TAKEDOWN_RESPONSE.es.md)

This is a working guide for REau's maintainer, kept in the open so that whoever takes over the project finds it. It says
what to do if Royal Enfield, or any other rights holder, asks for something of REau to be removed. It is **not legal
advice**: it is a checklist built on public facts. When a request arrives, talk to a lawyer who knows software and
copyright, and let that lawyer decide what the checklist leaves open (see "Who to ask for help").

## What happened to two similar projects

Only what the projects themselves published:

- **NorthStar**, an open project for the same dash, had its author remove the whole source on 2026-07-08 (commit
  `f33f183`, "Remove source, project discontinued at Royal Enfield's request"). The repository was left with a short
  README and was archived later. No notice appears in GitHub's public repository of takedown notices (searched on
  2026-11-18), so the request seems to have reached the author directly. Forks made before the removal stayed online.
- **open-dash**, a project that descends from NorthStar, says in its README that Royal Enfield contacted it and, after
  discussions, it removes the dash connection and projection protocols, proprietary code and dash wallpaper from future
  releases. On 2026-09-21 its main branch was rewritten into a single commit (`a55fa90`) without that code.
- Neither project published the grounds of the request. Both had a "not affiliated" notice and a NOTICE file, and
  that did not prevent the request: the notice helps, but it is not a shield.
- When a project goes, its history may go with it, and so may the evidence of its license. REau's NOTICE now cites
  commits that still open, for that reason.

## The first 24 hours

1. Do not answer in anger, do not argue in public, and do not delete anything yet. Deleting looks like hiding and
   destroys what you need.
2. Save the request exactly as it came: the full text, the sender, the date and time, the headers and the attachments, in
   a folder that only the maintainer reads. Write down when you received it.
3. Check who sent it. A message from a company or a law firm should come from an address you can confirm by a channel
   you find yourself (the company's own site), not by the links in the message. Do not open attachments from a sender
   you cannot confirm.
4. Find out what kind of request it is: a friendly message, a letter from a lawyer, a notice that GitHub forwards (it
   carries a deadline and instructions: read them the same day), or a court order. Each one asks for a different answer.
5. Pause what could make things worse: no new release, tag, video or announcement until the request is understood. Do
   not touch the code that is not mentioned.
6. Take a private copy of everything as it is now: a full mirror of the repository (`git clone --mirror`), a list of the
   releases with the hash of each APK, and the pages of the site. It is your record of what was published and when.
7. Send a short acknowledgement, without admitting or denying anything: that the message arrived, that the project is
   run by one person in spare time, and that an answer will follow by a date you can keep (a few days).
8. Look for a lawyer the same day (see "Who to ask for help").

## How to assess the request

Ask what exactly is being claimed, by whom, and on what right. A request often mixes three different things:

- **Trademark.** The names Royal Enfield, Tripper, Himalayan and the like, logos, product icons and the look of the
  official app. REau names the hardware only to say what it works with, and its README, NOTICE and Credits say it is
  not affiliated. Check every place that could suggest otherwise: the icon, the screenshots, the site, the texts of the
  app, the release notes. Wording and images are easy to change.
- **Copyright in material.** Code, images, texts, translations, keys or firmware of the official app. REau's rule is
  that none of it is in the repository, in the history or in the APK, and the maintainer re-checks this whenever
  a claim names a file. If the claimant points to something that is really there, remove it at once, from the
  current tree and from the releases, and tell the lawyer what the history holds.
- **Protocol facts.** Which byte the dash expects, and in what order, observed on the maintainer's own dash and written
  in REau's own code. Whether and how facts like these may be used for interoperability depends on the country, so this
  is the part for the lawyer, not for the maintainer alone.

Then consider the license side. REau is under the AGPL-3.0 and much of it comes from other projects (see NOTICE), so:

- A claim over a part that came from another project is answered with the NOTICE and the file headers, which say
  where each part came from, under which license, and at which commit. If the part is theirs, the NOTICE shows how to
  reach its authors.
- The AGPL-3.0 gives everyone who has a copy the right to keep and pass it on under the same terms. Taking REau's
  repository down does not take back copies that already exist, and REau cannot give a license over material that is not
  its own. Do not promise a recall that cannot be done.
- If the APK stays available, the matching source must stay available too (the AGPL asks for it). Taking down only the
  repository while the APK is still offered would break the license. Take both down, or neither.
- If the complaint is that a license was not followed (a missing notice, a wrong license), fix it first and write down
  when. The AGPL-3.0 lets a licensee who stops the violation recover the license, in the cases its section 8 describes.
- Contributions are only accepted with a known origin (see CONTRIBUTING), precisely so that this question can be
  answered.

A request is not an order. Even so, a formal notice that is ignored can end with the platform disabling the
repository, and a reply that is wrong can be used against the project. Take the decision with the lawyer.

## What to take down, and in this order

1. The material the claimant has identified as its own, if it is really there: the files, the strings, the images,
   the video frames. Remove it from the current tree, from the releases and from the site.
2. What is in dispute and cannot be settled quickly: withdraw that part only (a file, a release, a page of the site, a
   video) and leave the rest. A narrow removal is easy to reverse and shows good faith.
3. Everything that could be taken for the official app: the name, logos and screenshots that suggest a connection.
4. The whole project, only as a last resort, and by archiving the repository or making it private, not by deleting it.
   Remember the APKs already installed and the copies of the code that already exist: say so in the answer instead of
   promising what cannot be done.

The places to look, so that nothing is forgotten: the repository (tree, history, tags and branches), the releases and
their APKs, the site (`docs/`, its images and videos), the README, NOTICE and CONTRIBUTING, the Credits screen and
the texts inside the app, the issue templates and the open issues and pull requests.

If something has to leave the history, rewrite it only with the lawyer's agreement and say in the answer that forks and
old clones keep what they had. Write the change in the CHANGELOG in the three languages if it affects those who use the
app.

## How to answer

- Short, polite, factual and in writing. Use the language of the request, and also English if it was not English.
- Say who the project is: free software, one maintainer, a spare-time project, not affiliated with the rights holder, with
  no material of the official app.
- Ask for what is needed to act: which material, which files or addresses, which right, and who is speaking for the holder.
- Say what has already been done (for example, that releases are paused) and when the next answer will come.
- Do not threaten, do not accuse, do not guess the claimant's motives, and do not publish the private letter or the names
  of the people who wrote it. A short public note saying what was removed and why is enough.
- Do not send a counter-notice, do not state that the material is yours, and do not sign anything under penalty of
  perjury without a lawyer: those are legal acts with consequences for the person who signs.
- Keep every message of the exchange.

## What to keep

- The request, the answers and the dates, as they were sent and received.
- The mirror of the repository, the list of releases with their APK hashes and the pages of the site as they were before
  anything changed, and a list of what was changed afterwards, with the commit of each change.
- The record of how REau was checked for official material (what was searched, when, with what result).
- The license evidence of each project in NOTICE: copy the license text and the NOTICE of an upstream project into the
  repository's `licenses/` folder when that is allowed, and check from time to time that the addresses and commits cited in
  NOTICE still open (`gh api repos/OWNER/NAME/commits/HASH`). NorthStar's license text left
  with its source.
- Nothing of the claimed material, beyond the private copy of the mirror: do not redistribute it, and keep it apart.

## After the answer

- If the request was settled, write down what was agreed, and keep the reply of the other side.
- Review what led to it and close the gap: a README sentence, an image, a contribution that came in without a source.
- Do not contact the owners of forks in the name of the claimant, and do not attack them. They are not under the project's
  control.
- Tell the users plainly, in the three languages, if something they use has changed.

## Who to ask for help

A lawyer who knows software and copyright, in the country where the maintainer lives. Organizations that help free
software projects, such as the Software Freedom Conservancy or the Electronic Frontier Foundation, can also orient the
first conversation. None of what is written here replaces them, and none of it is a promise that a given step is safe.
