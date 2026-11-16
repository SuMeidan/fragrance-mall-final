import { createApp } from 'vue'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElLoading,
  ElConfigProvider,
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './style.css'
const app = createApp(App).use(router).use(ElLoading)
Object.entries({
  ElAlert,
  ElButton,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElConfigProvider,
}).forEach(([name, component]) => app.component(name, component))
app.mount('#app')
