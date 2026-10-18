import { describe, expect, test } from 'bun:test'
import { validationFailed } from '@/platform/http/respond'
import { shipInput } from './fulfillment/routes'
import { destinationInput } from './routes'

const errorsOf = (schema: typeof destinationInput | typeof shipInput, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

const sftp = {
  host: ' sftp.example.test ',
  username: 'producer',
  password: ' pass ',
  hostKeyFingerprint: 'SHA256:abc',
  timeoutSeconds: 30,
}
const spod = { environment: 'STAGING', accessToken: 'token', timeoutSeconds: 30 }

describe('destinationInput', () => {
  test('a complete body of each channel is normalized, defaulted, and keeps its secret apart', () => {
    expect(
      destinationInput.parse({ supplierId: 1, channel: ' SFTP ', label: ' Hotfolder ', sftp }),
    ).toEqual({
      supplierId: 1,
      label: 'Hotfolder',
      enabled: true,
      notificationEmail: null,
      notificationName: null,
      detail: {
        channel: 'SFTP',
        host: 'sftp.example.test',
        port: 22,
        username: 'producer',
        hostKeyFingerprint: 'SHA256:abc',
        remotePath: '/',
        timeoutSeconds: 30,
      },
      secret: ' pass ',
    })
    expect(
      destinationInput.parse({
        supplierId: 1,
        channel: 'SPOD',
        label: 'Spreadconnect',
        enabled: false,
        notificationEmail: ' ',
        spod: { ...spod, accessToken: '  ' },
      }),
    ).toMatchObject({
      enabled: false,
      notificationEmail: null,
      detail: { channel: 'SPOD', environment: 'STAGING', timeoutSeconds: 30 },
      secret: null,
    })
  })

  test('an empty body reports every required field it can decide on', () => {
    expect(errorsOf(destinationInput, {})).toEqual({
      supplierId: ['SupplierId is required'],
      channel: ['Channel is required'],
      label: ['Label is required'],
    })
  })

  test('empty detail blocks report every required detail field', () => {
    expect(
      errorsOf(destinationInput, { supplierId: 1, channel: 'SFTP', label: 'x', sftp: {} }),
    ).toEqual({
      'sftp.host': ['Host is required'],
      'sftp.username': ['Username is required'],
      'sftp.hostKeyFingerprint': ['HostKeyFingerprint is required'],
      'sftp.timeoutSeconds': ['TimeoutSeconds is required'],
    })
    expect(
      errorsOf(destinationInput, { supplierId: 1, channel: 'SPOD', label: 'x', spod: {} }),
    ).toEqual({
      'spod.environment': ['Environment is required'],
      'spod.timeoutSeconds': ['TimeoutSeconds is required'],
    })
  })

  test('exactly the block of the channel must be present; an unknown channel says nothing', () => {
    expect(errorsOf(destinationInput, { supplierId: 1, channel: 'SFTP', label: 'x' })).toEqual({
      channel: ['SFTP destinations require the sftp block'],
    })
    expect(
      errorsOf(destinationInput, { supplierId: 1, channel: 'SPOD', label: 'x', sftp, spod }),
    ).toEqual({ channel: ['SPOD destinations must not carry the sftp block'] })
    expect(
      errorsOf(destinationInput, { supplierId: 0, channel: 'FTP', label: '', spod: {} }),
    ).toEqual({
      supplierId: ['SupplierId must be positive'],
      channel: ['Channel must be one of: SFTP, SPOD'],
      label: ['Label is required'],
      'spod.environment': ['Environment is required'],
      'spod.timeoutSeconds': ['TimeoutSeconds is required'],
    })
  })

  test('ranges, lengths, the email shape, and the environment are checked', () => {
    const long = 'x'.repeat(256)
    expect(
      errorsOf(destinationInput, {
        supplierId: 1,
        channel: 'SFTP',
        label: long,
        notificationEmail: 'no-at-sign',
        notificationName: long,
        sftp: {
          ...sftp,
          port: 70000,
          password: long,
          remotePath: 'p'.repeat(1025),
          timeoutSeconds: 0,
        },
      }),
    ).toEqual({
      label: ['Label must be at most 255 characters'],
      notificationEmail: ['NotificationEmail must be a valid email address'],
      notificationName: ['NotificationName must be at most 255 characters'],
      'sftp.port': ['Port must be between 1 and 65535'],
      'sftp.password': ['Password must be at most 255 characters'],
      'sftp.remotePath': ['RemotePath must be at most 1024 characters'],
      'sftp.timeoutSeconds': ['TimeoutSeconds must be between 1 and 3600'],
    })
    expect(
      errorsOf(destinationInput, {
        supplierId: 1,
        channel: 'SPOD',
        label: 'x',
        spod: { environment: 'LOCAL', accessToken: 't'.repeat(513), timeoutSeconds: 3601 },
      }),
    ).toEqual({
      'spod.environment': ['Environment must be one of: PRODUCTION, STAGING'],
      'spod.accessToken': ['AccessToken must be at most 512 characters'],
      'spod.timeoutSeconds': ['TimeoutSeconds must be between 1 and 3600'],
    })
  })

  test('a rejected body never echoes its secret', () => {
    const body = { supplierId: 1, channel: 'SFTP', label: '', sftp: { password: 'hunter2' } }
    expect(JSON.stringify(errorsOf(destinationInput, body))).not.toContain('hunter2')
  })
})

describe('shipInput', () => {
  test('an empty body ships without carrier or number; blank is absent and text is trimmed', () => {
    expect(shipInput.parse({})).toEqual({
      carrier: null,
      trackingNumber: null,
      reportedCarrierName: null,
    })
    expect(shipInput.parse({ carrier: ' DHL ', trackingNumber: ' 0034 ' })).toEqual({
      carrier: 'DHL',
      trackingNumber: '0034',
      reportedCarrierName: null,
    })
    expect(shipInput.parse({ carrier: ' ', trackingNumber: '' })).toMatchObject({
      carrier: null,
      trackingNumber: null,
    })
  })

  test('an unknown carrier and an unusable number are field errors', () => {
    expect(errorsOf(shipInput, { carrier: 'FEDEX', trackingNumber: 'x'.repeat(129) })).toEqual({
      carrier: ['Carrier must be one of: DHL, DPD, GLS, HERMES, UPS, DEUTSCHE_POST, OTHER'],
      trackingNumber: ['TrackingNumber must be at most 128 characters'],
    })
    expect(errorsOf(shipInput, { trackingNumber: 'a\u0007b' })).toEqual({
      trackingNumber: ['TrackingNumber must not contain control characters'],
    })
  })
})
