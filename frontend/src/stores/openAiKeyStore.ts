import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useOpenAiKeyStore = defineStore('openAiKey', () => {
  const apiKey = ref<string>('')

  function setKey(key: string) {
    apiKey.value = key
  }

  function clearKey() {
    apiKey.value = ''
  }

  function hasKey(): boolean {
    return apiKey.value.length > 0
  }

  return { apiKey, setKey, clearKey, hasKey }
})
