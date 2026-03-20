<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 backdrop-blur-sm p-4" @click.self="$emit('update:modelValue', false)">
      <div class="bg-white rounded-2xl shadow-2xl border border-slate-100 p-6 w-full max-w-md" @click.stop>
        <h2 class="text-xl font-bold text-slate-800 mb-1">OpenAI API Key</h2>
        <p class="text-sm text-slate-500 mb-4">Stored in memory only for this session. Never sent to our servers except when calling search or processing.</p>
        <input
          v-model="localKey"
          type="password"
          placeholder="sk-..."
          class="w-full px-4 py-3 border-2 border-slate-200 rounded-xl focus:border-brand-400 focus:ring-2 focus:ring-brand-100 outline-none transition-all placeholder:text-slate-400"
          autocomplete="off"
        />
        <div class="flex gap-3 mt-5">
          <button
            class="flex-1 px-4 py-2.5 rounded-xl font-semibold text-white bg-gradient-to-r from-brand-500 to-indigo-600 hover:from-brand-600 hover:to-indigo-700 shadow-lg shadow-brand-500/25 transition-all"
            @click="save"
          >
            Save
          </button>
          <button
            class="px-4 py-2.5 rounded-xl font-semibold border-2 border-slate-200 text-slate-600 hover:bg-slate-50 hover:border-slate-300 transition-all"
            @click="$emit('update:modelValue', false)"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useOpenAiKeyStore } from '../stores/openAiKeyStore'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const store = useOpenAiKeyStore()
const localKey = ref('')

watch(() => props.modelValue, (open) => {
  if (open) localKey.value = store.apiKey
})

function save() {
  store.setKey(localKey.value.trim())
  emit('update:modelValue', false)
}
</script>
