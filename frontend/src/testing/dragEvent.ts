export function createDragEvent(type: string) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as Event & {
    dataTransfer: {
      effectAllowed: string
      dropEffect: string
      setData: (format: string, data: string) => void
    }
  }

  Object.defineProperty(event, 'dataTransfer', {
    value: {
      effectAllowed: '',
      dropEffect: '',
      setData: () => undefined,
    },
  })

  return event
}
