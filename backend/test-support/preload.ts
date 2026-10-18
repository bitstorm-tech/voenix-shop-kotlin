import { afterAll } from 'bun:test'
import { PostgreSqlContainer } from '@testcontainers/postgresql'
import { Client } from 'pg'
import { migrate } from '@/platform/db/migrate'
import { TEMPLATE_DATABASE } from '@test-support/database'

/**
 * Runs once per `bun test` run (bunfig.toml `[test] preload`): starts one PostgreSQL container,
 * creates the template database and migrates it, and hands the connection details to
 * `test-support/database.ts` through the environment. Every test file then copies the template.
 */

const container = await new PostgreSqlContainer('postgres:18-alpine').start()
process.env.TEST_DATABASE_HOST = container.getHost()
process.env.TEST_DATABASE_PORT = String(container.getPort())
process.env.TEST_DATABASE_USERNAME = container.getUsername()
process.env.TEST_DATABASE_PASSWORD = container.getPassword()
process.env.TEST_DATABASE_SERVER_NAME = container.getDatabase()

const server = new Client({
  host: container.getHost(),
  port: container.getPort(),
  user: container.getUsername(),
  password: container.getPassword(),
  database: container.getDatabase(),
})
await server.connect()
await server.query(`CREATE DATABASE ${TEMPLATE_DATABASE}`)
await server.end()

// PostgreSQL copies a template only while nobody is connected to it; `migrate` ends its client.
await migrate({
  host: container.getHost(),
  port: container.getPort(),
  name: TEMPLATE_DATABASE,
  username: container.getUsername(),
  password: container.getPassword(),
  searchPath: 'voenix',
  sslMode: 'disable',
  maximumPoolSize: 1,
})

afterAll(async () => {
  await container.stop()
})
