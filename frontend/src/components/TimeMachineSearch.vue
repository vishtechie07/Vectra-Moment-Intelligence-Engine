<template>
  <div class="space-y-4">
    <div class="flex items-center gap-2">
      <span class="w-8 h-8 rounded-lg bg-gradient-to-br from-brand-500 to-indigo-600 flex items-center justify-center text-white text-sm font-bold">⌕</span>
      <label class="text-base font-bold text-slate-800">Time Machine Search</label>
    </div>
    <input
      v-model="query"
      type="text"
      placeholder="e.g. person waving, dog running..."
      class="w-full px-4 py-3 rounded-xl border-2 border-slate-200 bg-white focus:border-brand-400 focus:ring-2 focus:ring-brand-100 outline-none transition-all placeholder:text-slate-400 text-slate-800"
      @keyup.enter="search"
    />
    <button
      class="w-full px-4 py-3 rounded-xl font-semibold text-white bg-gradient-to-r from-brand-500 to-indigo-600 hover:from-brand-600 hover:to-indigo-700 shadow-lg shadow-brand-500/25 hover:shadow-brand-500/40 disabled:opacity-50 disabled:shadow-none transition-all duration-200"
      :disabled="loading || !keyStore.hasKey()"
      @click="search"
    >
      {{ loading ? 'Searching...' : 'Search' }}
    </button>
    <ul v-if="hits.length" class="divide-y divide-slate-100 max-h-80 overflow-y-auto scroll-result space-y-0">
      <li
        v-for="(hit, i) in hits"
        :key="i"
        class="py-3 px-3 rounded-xl cursor-pointer hover:bg-brand-50/80 transition-colors -mx-1"
        @click="emit('seek', hit.timestampSeconds)"
      >
        <span class="inline-block text-xs font-bold text-brand-600 bg-brand-100 px-2 py-0.5 rounded-md">{{ formatTime(hit.timestampSeconds) }}</span>
        <p class="text-sm text-slate-600 mt-1.5">{{ hit.snippet }}</p>
      </li>
    </ul>
    <p v-else-if="searchError" class="text-sm font-medium text-amber-700 bg-amber-50 border border-amber-100 rounded-xl px-3 py-2">{{ searchError }}</p>
    <p v-else-if="searched && !loading" class="text-sm text-slate-500 rounded-xl bg-slate-50 px-3 py-2 border border-slate-100">No results. Try different words (e.g. person, hand, screen) or wait a few seconds after processing.</p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import client from '../api/client'
import { useOpenAiKeyStore } from '../stores/openAiKeyStore'
import type { AxiosError } from 'axios'

const props = defineProps<{ videoId?: string | null }>()
const emit = defineEmits<{ (e: 'seek', seconds: number): void }>()

const keyStore = useOpenAiKeyStore()
const query = ref('')
const loading = ref(false)
const searched = ref(false)
const searchError = ref('')
const hits = ref<Array<{ videoId: string; timestampSeconds: number; snippet: string; score: number }>>([])

function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}

async function search() {
  const q = query.value.trim()
  if (!q) return
  loading.value = true
  searched.value = true
  hits.value = []
  searchError.value = ''
  try {
    const params = new URLSearchParams({ q })
    if (props.videoId) params.set('videoId', props.videoId)
    const { data } = await client.get<{ hits: typeof hits.value }>('/search', { params })
    hits.value = data.hits ?? []
  } catch (err) {
    hits.value = []
    const ax = err as AxiosError<{ message?: string }>
    searchError.value = ax.response?.data?.message ?? (ax.response?.status === 401 ? 'Set or fix your OpenAI API key.' : ax.response?.status === 503 ? 'Search unavailable. Start Docker (OpenSearch).' : 'Search failed.')
  } finally {
    loading.value = false
  }
}
</script>
