import { migrate } from '@/platform/db/migrate'
import { loadEnvFiles, loadSettings } from '@/platform/settings'

loadEnvFiles()
const applied = await migrate(loadSettings(process.env).database)
console.log(applied.length === 0 ? 'The schema is up to date.' : `Applied ${applied.join(', ')}`)
