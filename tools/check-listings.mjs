/**
 * Check the store listing source (LIST-3, LIST-5, LIST-7): store-assets/play/listings.json has schemaVersion 1,
 * exactly the app's locales in the app's order (companion/src/main/res/xml/locales_config.xml), aliases that
 * point at a full record, and every field of every full record within Play's limits. Over-long text fails; it is
 * never truncated. The parent app's About tagline must also equal each locale's short description.
 *
 *   node tools/check-listings.mjs        (used by `npm run validate`)
 *
 * Length is counted in Unicode code points, which is how Play counts characters.
 */
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** Play's limits per field (LIST-5). */
export const PLAY_LIMITS = { title: 30, shortDescription: 80, fullDescription: 4000 }

/** The locale identities the app declares, in order, from a locales_config.xml body. */
export function appLocales(localesConfigXml) {
  return [...localesConfigXml.matchAll(/<locale\s+android:name="([^"]+)"/g)].map((m) => m[1])
}

/**
 * Problems in one listing source (empty when valid). [source] is the parsed listings.json and [locales] the app's
 * locale identities in order.
 */
export function problemsInListing(source, locales) {
  const problems = []
  if (source?.schemaVersion !== 1) problems.push('schemaVersion must be 1')
  const records = source?.locales
  if (!records || typeof records !== 'object') return [...problems, 'locales must be an object']

  const keys = Object.keys(records)
  if (keys.join(',') !== locales.join(',')) {
    problems.push(`locales must be the app's, in its order: expected ${locales.join(', ')}; found ${keys.join(', ')}`)
  }

  for (const [identity, record] of Object.entries(records)) {
    if (record && 'copyFrom' in record) {
      // An alias shares another locale's copy; it must name a full record, not another alias.
      const target = records[record.copyFrom]
      if (!target || 'copyFrom' in target) problems.push(`${identity}: copyFrom must name a full locale record`)
      if (Object.keys(record).length !== 1) problems.push(`${identity}: an alias holds only copyFrom`)
      continue
    }
    for (const [field, limit] of Object.entries(PLAY_LIMITS)) {
      const text = record?.[field]
      if (typeof text !== 'string' || text.trim() === '') {
        problems.push(`${identity}: ${field} is missing`)
        continue
      }
      const length = [...text].length
      if (length > limit) problems.push(`${identity}: ${field} is ${length} characters, over Play's ${limit}`)
      if (text.includes('—')) problems.push(`${identity}: ${field} has an em dash`)
    }
    const extra = Object.keys(record ?? {}).filter((f) => !(f in PLAY_LIMITS))
    if (extra.length) problems.push(`${identity}: unknown fields ${extra.join(', ')}`)
  }
  return problems
}

/** The parent app's wording folder for each full listing locale (aliases share their target's). */
export const CATALOG_FOLDERS = {
  'en-US': 'values',
  'es-ES': 'values-es',
  'fr-FR': 'values-fr',
  'de-DE': 'values-de',
  'zh-CN': 'values-b+zh+Hans',
  'pt-BR': 'values-pt',
  'ja-JP': 'values-ja',
}

/** The About tagline's text in a strings_about.xml body, with Android's escapes undone; null when absent. */
export function taglineIn(stringsXml) {
  const match = /<string name="about_tagline">([\s\S]*?)<\/string>/.exec(stringsXml)
  return match ? match[1].replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/&amp;/g, '&') : null
}

/**
 * Problems where the About screen's tagline differs from the listing's short description: they are one sentence,
 * word for word, in every language. [taglines] maps locale identity to the catalog's tagline.
 */
export function problemsInTaglines(source, taglines) {
  const problems = []
  for (const [identity, tagline] of Object.entries(taglines)) {
    const short = source?.locales?.[identity]?.shortDescription
    if (tagline !== short) problems.push(`${identity}: about_tagline differs from the listing's shortDescription`)
  }
  return problems
}

function main() {
  const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
  const source = JSON.parse(readFileSync(join(root, 'store-assets/play/listings.json'), 'utf8'))
  const locales = appLocales(readFileSync(join(root, 'companion/src/main/res/xml/locales_config.xml'), 'utf8'))
  const res = join(root, 'companion/src/main/res')
  const taglines = Object.fromEntries(
    Object.entries(CATALOG_FOLDERS).map(([identity, folder]) => [
      identity,
      taglineIn(readFileSync(join(res, folder, 'strings_about.xml'), 'utf8')),
    ]),
  )
  const problems = [...problemsInListing(source, locales), ...problemsInTaglines(source, taglines)]
  if (problems.length) {
    console.error(problems.join('\n'))
    console.error(`check:listings: ${problems.length} problem(s)`)
    process.exit(1)
  }
  console.log(`check:listings: ${locales.length} locales within Play's limits`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
