/**
 * Browser demo recorder — requires app at APP_URL (default http://localhost:5173)
 * and OPENAI_KEY. Output: demo-recordings/*.mp4 (Playwright WebM + ffmpeg).
 */
import { spawnSync } from 'child_process'
import { chromium } from 'playwright'
import ffmpegPath from 'ffmpeg-static'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { join, resolve } from 'path'
import { fileURLToPath } from 'url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))
const projectRoot = resolve(__dirname, '../..')
const outDir = resolve(projectRoot, 'demo-recordings')

const appUrl = process.env.APP_URL || 'http://localhost:5173'
const openAiKey = process.env.OPENAI_KEY
const maxProcessingMs = Number(process.env.DEMO_MAX_WAIT_MS || 600_000)
const recordWidth = Number(process.env.DEMO_RECORD_WIDTH || 1920)
const recordHeight = Number(process.env.DEMO_RECORD_HEIGHT || 1080)
const ffmpegCrf = process.env.DEMO_FFMPEG_CRF || '18'

const DEFAULT_QUERIES = [
  'Are there donuts?',
  'Are there people who are seated?',
  'Is anyone inside a car?',
  'Is anyone being served?',
]

function parseSearchQueries() {
  const raw = process.env.DEMO_SEARCH_QUERIES?.trim()
  if (!raw) {
    if (process.env.DEMO_SEARCH_QUERY?.trim()) return [process.env.DEMO_SEARCH_QUERY.trim()]
    return DEFAULT_QUERIES
  }
  if (raw.startsWith('[')) {
    return JSON.parse(raw)
  }
  return raw.split('|').map((q) => q.trim()).filter(Boolean)
}

function findTestVideo() {
  if (process.env.DEMO_VIDEO_PATH) {
    const p = resolve(projectRoot, process.env.DEMO_VIDEO_PATH)
    if (!existsSync(p)) throw new Error(`DEMO_VIDEO_PATH not found: ${p}`)
    return p
  }
  for (const dir of ['test_videos', 'infra/test_videos']) {
    const full = join(projectRoot, dir)
    if (!existsSync(full)) continue
    const mp4 = readdirSync(full, { withFileTypes: true })
      .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.mp4'))
      .map((e) => join(full, e.name))[0]
    if (mp4) return mp4
  }
  throw new Error('No .mp4 found. Set DEMO_VIDEO_PATH in .env')
}

async function waitForProcessing(page) {
  const done = page.getByText(/Processing complete \(\d+ frames\)/)
  const failed = page.getByText(/Processing failed|No frames indexed/)
  const deadline = Date.now() + maxProcessingMs
  while (Date.now() < deadline) {
    if (await done.isVisible().catch(() => false)) return
    if (await failed.isVisible().catch(() => false)) {
      throw new Error('Processing failed — check backend logs and Docker.')
    }
    await page.waitForTimeout(2000)
  }
  throw new Error(`Processing did not finish within ${maxProcessingMs / 1000}s`)
}

async function playUploadedVideo(page) {
  console.log('Playing uploaded video…')
  const playButton = page.locator('.vjs-big-play-button')
  await playButton.waitFor({ state: 'visible', timeout: 60_000 })
  await playButton.click()
  await page.locator('video').evaluate((v) => v.play().catch(() => {}))
  await page.waitForTimeout(2000)
}

async function waitForSearchResults(page) {
  const searchBtn = page.getByRole('button', { name: /^Search$/i })
  await page.waitForFunction(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => /^Search$/i.test(b.textContent?.trim() ?? ''))
    return btn && !/Searching/i.test(btn.textContent ?? '')
  }, { timeout: 60_000 })
  await searchBtn.waitFor({ state: 'visible', timeout: 5_000 })
  await page.waitForTimeout(500)
}

async function runSearch(page, query) {
  console.log('Search:', query)
  const searchInput = page.getByPlaceholder(/person waving|dog running|Available when/i)
  await searchInput.waitFor({ state: 'visible', timeout: 30_000 })
  await searchInput.fill(query)
  await page.getByRole('button', { name: /^Search$/i }).click()
  await waitForSearchResults(page)

  const hits = page.locator('ul.scroll-result li')
  const count = await hits.count()
  if (count === 0) {
    console.log('  No results')
    await page.waitForTimeout(2000)
    return
  }
  console.log(`  ${count} result(s)`)
  for (let i = 0; i < count; i++) {
    await hits.nth(i).scrollIntoViewIfNeeded()
    await hits.nth(i).click()
    await page.waitForTimeout(3500)
  }
}

function convertWebmToMp4(webmPath) {
  if (!ffmpegPath) {
    throw new Error('ffmpeg binary not found — run npm install in scripts/demo-record')
  }
  const mp4Path = webmPath.replace(/\.webm$/i, '.mp4')
  const result = spawnSync(
    ffmpegPath,
    [
      '-y', '-i', webmPath,
      '-c:v', 'libx264',
      '-crf', ffmpegCrf,
      '-preset', 'slow',
      '-pix_fmt', 'yuv420p',
      '-movflags', '+faststart',
      mp4Path,
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  )
  if (result.status !== 0) {
    const detail = result.stderr?.toString().trim()
    throw new Error(`ffmpeg failed (exit ${result.status ?? 'unknown'})${detail ? `: ${detail}` : ''}`)
  }
  unlinkSync(webmPath)
  return mp4Path
}

async function main() {
  if (!openAiKey?.trim()) {
    console.error('Set OPENAI_KEY (never commit it).')
    process.exit(1)
  }

  const searchQueries = parseSearchQueries()
  mkdirSync(outDir, { recursive: true })
  const videoPath = findTestVideo()
  console.log('App URL:', appUrl)
  console.log('Video:', videoPath)
  console.log('Queries:', searchQueries.length)
  console.log('Output dir:', outDir)
  console.log('Record size:', `${recordWidth}x${recordHeight}`)

  const browser = await chromium.launch({
    headless: false,
    args: [
      `--window-size=${recordWidth},${recordHeight}`,
      '--force-device-scale-factor=1',
    ],
  })
  const context = await browser.newContext({
    viewport: { width: recordWidth, height: recordHeight },
    deviceScaleFactor: 1,
    recordVideo: { dir: outDir, size: { width: recordWidth, height: recordHeight } },
  })
  const page = await context.newPage()

  try {
    await page.goto(appUrl, { waitUntil: 'networkidle', timeout: 60_000 })
    await page.evaluate(() => {
      document.documentElement.style.overflowX = 'hidden'
      document.body.style.overflowX = 'hidden'
    })

    const keyBtn = page.locator('header').getByRole('button', { name: /Set OpenAI Key|Update API Key/i })
    if (await keyBtn.isVisible()) await keyBtn.click()
    await page.locator('input[type="password"]').fill(openAiKey)
    await page.getByRole('button', { name: 'Save' }).click()

    await page.locator('input[type="file"]').setInputFiles(videoPath)
    await page.getByText(/Queued for processing|Extracting frames|Analyzing and indexing/i).waitFor({ timeout: 120_000 })
    await playUploadedVideo(page)
    console.log('Uploaded; waiting for processing…')
    await waitForProcessing(page)
    await page.getByRole('button', { name: /^Search$/i }).waitFor({ state: 'visible', timeout: 30_000 })

    for (const query of searchQueries) {
      await runSearch(page, query)
    }

    await page.waitForTimeout(2000)
  } catch (err) {
    console.error('Recording error:', err.message || err)
    throw err
  } finally {
    const video = page.video()
    const savedPath = video
      ? join(outDir, `vectramoment-demo-${Date.now()}.webm`)
      : null
    try {
      await context.close()
      if (video && savedPath) {
        try {
          await video.saveAs(savedPath)
          const mp4Path = convertWebmToMp4(savedPath)
          console.log('Saved:', mp4Path)
        } catch (e) {
          console.error('Could not save recording:', e.message || e)
        }
      }
    } finally {
      await browser.close()
    }
  }
}

main().catch((err) => {
  console.error(err.message || err)
  process.exit(1)
})
