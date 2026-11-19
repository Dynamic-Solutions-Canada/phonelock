/**
 * Committed release notes (REL-6): check them, and turn one version's notes into the GitHub Release body.
 * Ported from SonarBridge's scripts/release-notes.mjs, without its Play step (PhoneLock is not on Play yet).
 *
 *   node scripts/deploy/release-notes.mjs check
 *       Validate every file in release-notes/ (used by `npm run validate`).
 *   node scripts/deploy/release-notes.mjs github vX.Y.Z
 *       Print the GitHub Release body: the en-US notes, how to get the apps, and the AI disclosure line.
 *
 * Notes are never generated from commits and never truncated: a missing sibling
 * or over-long text fails the command.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** The source catalog: release-notes/<version>.md holds this identity's text. */
export const SOURCE_IDENTITY = 'en-US'

/** Every other wording catalog the app ships, one sibling file each. */
export const SIBLING_IDENTITIES = ['es-ES', 'fr-FR', 'de-DE', 'zh-CN', 'pt-BR', 'ja-JP']

/** Play's "What's new" limit, the shortest store the notes will go to once the parent app is on Play (LIST-10). */
export const PLAY_NOTES_LIMIT = 500

/** Appended by CI to every GitHub Release (D-097); never part of the notes file. */
export const DISCLOSURE_LINE = '*AI disclosure: this release and the related code were written with the assistance of AI.*'

const FILE_NAME = /^(\d+\.\d+\.\d+)(?:\.([a-z]{2}-[A-Z]{2}))?\.md$/

/** Turn a vX.Y.Z tag into the X.Y.Z version used in file names; throws on any other form. */
export function versionFromTag(tag) {
  const match = /^v(\d+\.\d+\.\d+)$/.exec(tag ?? '')
  if (!match) throw new Error(`Expected a tag like v1.2.3, got "${tag}".`)
  return match[1]
}

/**
 * Validate one notes body. Returns a list of problems (empty when valid).
 * Length is counted in Unicode code points, which is how Play counts characters.
 */
export function problemsInNotes(text) {
  const problems = []
  const body = text.trim()
  if (body === '') problems.push('is empty')
  if ([...body].length > PLAY_NOTES_LIMIT) problems.push(`is ${[...body].length} characters; Play allows ${PLAY_NOTES_LIMIT}`)
  if (/^#/m.test(body)) problems.push('contains a heading; CI adds the title')
  if (/AI disclosure/i.test(body)) problems.push('contains the AI disclosure line; CI appends it')
  return problems
}

/**
 * Check every file in the notes folder: names follow the grammar, each body is
 * valid, and every version has the source file and all siblings.
 * Returns a list of human-readable problems.
 */
export function checkNotesFolder(folder) {
  // No notes have been written yet (the folder is created by the first release).
  if (!existsSync(folder)) return []

  const problems = []
  const versions = new Map()

  for (const name of readdirSync(folder)) {
    const match = FILE_NAME.exec(name)
    if (!match) {
      problems.push(`${name}: not <version>.md or <version>.<identity>.md`)
      continue
    }

    const [, version, identity = SOURCE_IDENTITY] = match
    if (identity !== SOURCE_IDENTITY && !SIBLING_IDENTITIES.includes(identity)) {
      problems.push(`${name}: ${identity} is not one of the app's wording catalogs`)
      continue
    }

    for (const problem of problemsInNotes(readFileSync(join(folder, name), 'utf8'))) {
      problems.push(`${name}: ${problem}`)
    }

    if (!versions.has(version)) versions.set(version, new Set())
    versions.get(version).add(identity)
  }

  for (const [version, identities] of versions) {
    for (const identity of [SOURCE_IDENTITY, ...SIBLING_IDENTITIES]) {
      if (!identities.has(identity)) problems.push(`${version}: missing ${fileFor(version, identity)}`)
    }
  }

  return problems
}

/** File name for one version and identity. */
function fileFor(version, identity) {
  return identity === SOURCE_IDENTITY ? `${version}.md` : `${version}.${identity}.md`
}

/** Read one version's notes for every catalog; throws if any file is missing or invalid. */
export function readVersionNotes(folder, version) {
  const notes = {}

  for (const identity of [SOURCE_IDENTITY, ...SIBLING_IDENTITIES]) {
    const path = join(folder, fileFor(version, identity))
    if (!existsSync(path)) throw new Error(`Release notes missing: release-notes/${fileFor(version, identity)}. Commit them before publishing.`)

    const text = readFileSync(path, 'utf8').trim()
    const problems = problemsInNotes(text)
    if (problems.length > 0) throw new Error(`release-notes/${fileFor(version, identity)} ${problems.join('; ')}`)
    notes[identity] = text
  }

  return notes
}

/**
 * The GitHub Release body: en-US notes, how to get the two apps, then the disclosure line.
 *
 * @param notes notes by identity, as readVersionNotes returns them.
 */
export function githubReleaseBody(notes) {
  return [
    notes[SOURCE_IDENTITY],
    '',
    '## Get it',
    '',
    'Install `PhoneLock-parent.apk` on your own phone; it updates itself from here. The lock app for the child\'s phone (`lock-release.apk`) is installed by the parent app during setup, and keeps itself up to date.',
    '',
    DISCLOSURE_LINE,
    ''
  ].join('\n')
}

/** Command-line entry point; exits non-zero with a message on any problem. */
function main(argv) {
  const repoRoot = resolve(fileURLToPath(new URL('../..', import.meta.url)))
  const folder = join(repoRoot, 'release-notes')
  const [command, tag] = argv

  if (command === 'check') {
    const problems = checkNotesFolder(folder)
    if (problems.length > 0) {
      for (const problem of problems) console.error(`release-notes/${problem}`)
      process.exit(1)
    }
    return
  }

  if (command === 'github') {
    const version = versionFromTag(tag)
    process.stdout.write(githubReleaseBody(readVersionNotes(folder, version)))
    return
  }

  console.error('Usage: release-notes.mjs check | github vX.Y.Z')
  process.exit(2)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
}
