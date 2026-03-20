import axios from 'axios'
import { useOpenAiKeyStore } from '../stores/openAiKeyStore'

const client = axios.create({
  baseURL: '/api',
  timeout: 30000
})

client.interceptors.request.use((config) => {
  const store = useOpenAiKeyStore()
  if (store.apiKey) {
    config.headers['X-OpenAI-Key'] = store.apiKey
  }
  return config
})

export default client
