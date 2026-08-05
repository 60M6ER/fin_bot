import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import os from 'node:os'

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const requiredMajor = 20
const args = process.argv.slice(2)

function parseVersion (value) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)/.exec(value)
  return match ? match.slice(1).map(Number) : null
}

function compareVersionsDesc (a, b) {
  const av = parseVersion(a)
  const bv = parseVersion(b)
  if (!av || !bv) return a.localeCompare(b)

  for (let i = 0; i < 3; i++) {
    if (av[i] !== bv[i]) return bv[i] - av[i]
  }
  return 0
}

function findNode20 () {
  const exact = join(os.homedir(), '.nvm/versions/node/v20.19.5/bin/node')
  if (existsSync(exact)) return exact

  const versionsDir = join(os.homedir(), '.nvm/versions/node')
  if (!existsSync(versionsDir)) return null

  const version = readdirSync(versionsDir)
    .filter(name => name.startsWith('v20.'))
    .sort(compareVersionsDesc)[0]

  return version ? join(versionsDir, version, 'bin/node') : null
}

function ensureNode20 () {
  const currentMajor = Number(process.versions.node.split('.')[0])
  if (currentMajor === requiredMajor) return

  const node20 = findNode20()
  if (!node20) {
    console.error(`Frontend requires Node 20. Current Node is ${ process.version }, and no nvm Node 20 was found.`)
    process.exit(1)
  }

  const result = spawnSync(node20, [ fileURLToPath(import.meta.url), ...args ], {
    cwd: projectRoot,
    stdio: 'inherit',
    env: process.env
  })

  process.exit(result.status ?? 1)
}

function patchQuasarHtmlMinifier () {
  const file = join(projectRoot, 'node_modules/@quasar/app-vite/lib/utils/html-template.js')
  if (!existsSync(file)) return

  let content = readFileSync(file, 'utf8')
  if (content.includes('async function minifyHtml')) return
  if (!content.includes("import { minify } from 'html-minifier-terser'")) return

  content = content
    .replace("import { minify } from 'html-minifier-terser'\n", '')
    .replace(
      "export const attachMarkup = '<div id=\"q-app\"></div>'\n",
      `export const attachMarkup = '<div id="q-app"></div>'\n\nlet htmlMinifierPromise\n\nasync function minifyHtml (html, options) {\n  htmlMinifierPromise ??= import('html-minifier-terser')\n  const { minify } = await htmlMinifierPromise\n\n  return minify(html, options)\n}\n`
    )
    .replaceAll('await minify(', 'await minifyHtml(')

  writeFileSync(file, content)
}

ensureNode20()
patchQuasarHtmlMinifier()

const quasarBin = join(projectRoot, 'node_modules/@quasar/app-vite/bin/quasar.js')
const result = spawnSync(process.execPath, [ quasarBin, ...args ], {
  cwd: projectRoot,
  stdio: 'inherit',
  env: process.env
})

process.exit(result.status ?? 1)
