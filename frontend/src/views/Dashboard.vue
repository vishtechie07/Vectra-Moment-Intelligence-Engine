<template>
  <div class="min-h-screen p-4 sm:p-6 lg:p-8">
    <header class="flex flex-wrap justify-between items-center gap-4 mb-6 sm:mb-8">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-brand-500 to-indigo-600 shadow-lg flex items-center justify-center text-white font-bold text-lg">V</div>
        <div>
          <h1 class="text-2xl sm:text-3xl font-extrabold bg-gradient-to-r from-brand-600 to-indigo-600 bg-clip-text text-transparent">VectraMoment</h1>
          <p class="text-xs sm:text-sm text-slate-500 font-medium">Semantic Video Intelligence</p>
        </div>
      </div>
      <button
        class="px-4 py-2.5 rounded-xl font-semibold text-sm bg-white border-2 border-brand-200 text-brand-700 hover:bg-brand-50 hover:border-brand-300 hover:shadow-card transition-all duration-200"
        @click="showKeyModal = true"
      >
        {{ keyStore.hasKey() ? 'Update API Key' : 'Set OpenAI Key' }}
      </button>
    </header>

    <KeyModal v-model="showKeyModal" />

    <div class="mb-6 rounded-2xl bg-white/90 backdrop-blur shadow-card border border-slate-100 p-5 sm:p-6">
      <p v-if="backendReachable === true" class="inline-flex items-center gap-2 text-sm font-medium text-accent-emerald mb-3 px-3 py-1.5 rounded-full bg-emerald-50 border border-emerald-100">
        <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" /> Backend connected
      </p>
      <p v-else-if="backendReachable === false" class="inline-flex items-center gap-2 text-sm font-medium text-accent-amber mb-3 px-3 py-1.5 rounded-full bg-amber-50 border border-amber-100">
        <span class="w-2 h-2 rounded-full bg-amber-500" /> Backend unreachable. Start backend (port 8081), then refresh this page.
      </p>
      <label class="block text-sm font-semibold text-slate-700 mb-2">Upload video</label>
      <div class="flex flex-col sm:flex-row gap-3 items-start">
        <label class="flex-1 w-full cursor-pointer rounded-xl border-2 border-dashed border-brand-200 bg-brand-50/50 hover:bg-brand-50 hover:border-brand-300 transition-colors p-4 text-center">
          <input
            type="file"
            accept="video/*"
            class="hidden"
            @change="onFileSelect"
          />
          <span class="text-sm font-medium text-brand-700">Choose file</span>
          <span class="text-slate-500 text-sm block mt-0.5">or drag and drop</span>
        </label>
      </div>
      <p v-if="uploadStatus" class="mt-3 text-sm font-medium" :class="uploadError ? 'text-rose-600' : 'text-slate-600'">{{ uploadStatus }}</p>
    </div>

    <div class="grid gap-6 lg:grid-cols-3">
      <div class="lg:col-span-2 rounded-2xl overflow-hidden bg-white/90 backdrop-blur shadow-card border border-slate-100 p-3 sm:p-4">
        <VideoPlayer
          ref="playerRef"
          :src="playbackUrl"
          :video-id="currentVideoId"
        />
      </div>
      <div class="rounded-2xl bg-white/90 backdrop-blur shadow-card border border-slate-100 p-5 sm:p-6">
        <TimeMachineSearch
          :video-id="currentVideoId"
          @seek="onSeek"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import KeyModal from '../components/KeyModal.vue'
import VideoPlayer from '../components/VideoPlayer.vue'
import TimeMachineSearch from '../components/TimeMachineSearch.vue'
import { useOpenAiKeyStore } from '../stores/openAiKeyStore'
import client from '../api/client'

const keyStore = useOpenAiKeyStore()
const showKeyModal = ref(false)
const playerRef = ref<{ seekTo: (s: number) => void } | null>(null)
const playbackUrl = ref<string | null>(null)
const currentVideoId = ref<string | null>(null)
const uploadStatus = ref('')
const uploadError = ref(false)
const backendReachable = ref<boolean | null>(null)
let processingPollTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  try {
    await client.get('/health')
    backendReachable.value = true
  } catch {
    backendReachable.value = false
  }
})

async function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploadStatus.value = 'Uploading...'
  uploadError.value = false
  try {
    const form = new FormData()
    form.append('file', file)
    const { data } = await client.post<{ videoId: string; s3Key: string }>('/videos/upload', form, { timeout: 300000 })
    currentVideoId.value = data.videoId
    const { data: urlData } = await client.get<{ url: string }>(`/videos/${data.videoId}/playback-url`)
    playbackUrl.value = urlData.url
    uploadStatus.value = 'Processing…'
    startProcessingPoll(data.videoId)
  } catch (err: unknown) {
    uploadError.value = true
    if (err && typeof err === 'object' && 'isAxiosError' in err && err.isAxiosError) {
      const ax = err as { message?: string; code?: string; response?: { status?: number } }
      if (ax.code === 'ERR_NETWORK' || ax.message === 'Network Error') {
        uploadStatus.value = 'Cannot reach backend. Start it (port 8081, local profile), then refresh this page and try again.'
      } else {
        uploadStatus.value = ax.response?.status ? `Upload failed (${ax.response.status})` : (ax.message ?? 'Upload failed')
      }
    } else {
      uploadStatus.value = err instanceof Error ? err.message : 'Upload failed'
    }
  }
  input.value = ''
}

function startProcessingPoll(videoId: string) {
  if (processingPollTimer) clearInterval(processingPollTimer)
  const pollIntervalMs = 3000
  const maxPolls = 80
  let polls = 0
  processingPollTimer = setInterval(async () => {
    polls++
    try {
      const { data } = await client.get<{ status: string; framesIndexed: number; message?: string }>(`/videos/${videoId}/processing-status`)
      if (data.status === 'ready') {
        if (processingPollTimer) clearInterval(processingPollTimer)
        processingPollTimer = null
        uploadStatus.value = `Processing complete (${data.framesIndexed} frames). You can use Time Machine search.`
        return
      }
      if (data.status === 'failed') {
        if (processingPollTimer) clearInterval(processingPollTimer)
        processingPollTimer = null
        uploadError.value = true
        uploadStatus.value = data.message || 'Processing failed. Check backend logs and retry.'
        return
      }
      if (data.status === 'queued') {
        uploadStatus.value = 'Queued for processing...'
      } else if (data.status === 'extracting') {
        uploadStatus.value = 'Extracting frames...'
      } else if (data.status === 'embedding') {
        uploadStatus.value = 'Analyzing and indexing frames...'
      } else {
        uploadStatus.value = 'Processing...'
      }
    } catch {
      // ignore; will retry
    }
    if (polls >= maxPolls) {
      if (processingPollTimer) clearInterval(processingPollTimer)
      processingPollTimer = null
      uploadStatus.value = 'Processing is taking longer than expected. Check status/logs and try search in a moment.'
    }
  }, pollIntervalMs)
}

function onSeek(seconds: number) {
  playerRef.value?.seekTo(seconds)
}
</script>
