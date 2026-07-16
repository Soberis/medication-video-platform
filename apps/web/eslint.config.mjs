import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'

export default defineConfigWithVueTs(
  {
    name: 'web/files-to-lint',
    files: ['**/*.{vue,ts,tsx,mts}'],
  },
  globalIgnores(['dist/**', 'coverage/**']),
  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
)
