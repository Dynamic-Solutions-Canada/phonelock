/** The listing source matches the app's locales, aliases resolve, and over-long text fails instead of being cut. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PLAY_LIMITS, appLocales, problemsInListing, problemsInTaglines, taglineIn } from './check-listings.mjs'

const locales = ['en-US', 'en-CA', 'fr-FR']
const record = { title: 'PhoneLock', shortDescription: 'Short.', fullDescription: 'Long.' }

/** A valid source for [locales], with optional overrides per locale. */
function source(overrides = {}) {
  return {
    schemaVersion: 1,
    locales: { 'en-US': record, 'en-CA': { copyFrom: 'en-US' }, 'fr-FR': record, ...overrides },
  }
}

test('A complete source passes', () => {
  assert.deepEqual(problemsInListing(source(), locales), [])
})

test("The app's locales are read in order from locales_config.xml", () => {
  const xml = '<locale-config><locale android:name="en-US" />\n<locale android:name="fr-CA" /></locale-config>'
  assert.deepEqual(appLocales(xml), ['en-US', 'fr-CA'])
})

test('Text over a limit is rejected, counted in characters', () => {
  const long = 'é'.repeat(PLAY_LIMITS.shortDescription + 1)
  const problems = problemsInListing(source({ 'fr-FR': { ...record, shortDescription: long } }), locales)
  assert.equal(problems.length, 1)
  assert.match(problems[0], /fr-FR: shortDescription is 81 characters/)
  assert.deepEqual(problemsInListing(source({ 'fr-FR': { ...record, shortDescription: 'é'.repeat(80) } }), locales), [])
})

test('A missing field or locale fails', () => {
  assert.match(problemsInListing(source({ 'fr-FR': { ...record, title: '' } }), locales)[0], /title is missing/)
  const { 'fr-FR': _, ...rest } = source().locales
  assert.match(problemsInListing({ schemaVersion: 1, locales: rest }, locales)[0], /locales must be the app's/)
})

test('Locales in another order fail', () => {
  const reordered = { schemaVersion: 1, locales: { 'fr-FR': record, 'en-US': record, 'en-CA': { copyFrom: 'en-US' } } }
  assert.match(problemsInListing(reordered, locales)[0], /in its order/)
})

test('An alias must point at a full record and hold nothing else', () => {
  assert.match(problemsInListing(source({ 'en-CA': { copyFrom: 'de-DE' } }), locales)[0], /copyFrom must name/)
  assert.match(problemsInListing(source({ 'en-CA': { copyFrom: 'en-US', title: 'x' } }), locales)[0], /only copyFrom/)
})

test('An em dash or an unknown field fails', () => {
  assert.match(problemsInListing(source({ 'fr-FR': { ...record, title: 'Phone—Lock' } }), locales)[0], /em dash/)
  assert.match(problemsInListing(source({ 'fr-FR': { ...record, keywords: 'x' } }), locales)[0], /unknown fields/)
})

test('schemaVersion must be 1', () => {
  assert.match(problemsInListing({ ...source(), schemaVersion: 2 }, locales)[0], /schemaVersion/)
})

test('The About tagline must be the short description, word for word', () => {
  // Android escapes the apostrophe in the catalog: Your child\'s phone.
  const xml = `<string name="about_tagline">Your child${'\\'}'s phone.</string>`
  assert.equal(taglineIn(xml), "Your child's phone.")
  assert.deepEqual(problemsInTaglines(source(), { 'en-US': 'Short.' }), [])
  assert.match(problemsInTaglines(source(), { 'fr-FR': 'Autre.' })[0], /fr-FR: about_tagline differs/)
})
