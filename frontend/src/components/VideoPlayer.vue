<template>
  <div class="bg-slate-900 rounded-xl overflow-hidden aspect-video shadow-card border border-slate-200/50">
    <video
      ref="videoEl"
      class="video-js vjs-big-play-centered"
      controls
      preload="auto"
      :src="src ?? undefined"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import videojs from 'video.js'
import 'video.js/dist/video-js.css'

const props = defineProps<{ src?: string | null; videoId?: string | null }>()

const videoEl = ref<HTMLVideoElement | null>(null)
let player: ReturnType<typeof videojs> | null = null

onMounted(() => {
  if (!videoEl.value) return
  player = videojs(videoEl.value, { fluid: true })
})

watch(() => props.src, (url) => {
  if (player && url) {
    player.src({ type: 'video/mp4', src: url })
  }
})

function seekTo(seconds: number) {
  if (player && !player.paused()) {
    player.currentTime(seconds)
  } else if (player) {
    player.currentTime(seconds)
  }
}

defineExpose({ seekTo })
</script>
