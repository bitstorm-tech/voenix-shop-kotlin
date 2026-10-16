/** The `fetch` an outbound provider client calls; tests hand in a stub of the same shape. */
export type Fetch = (url: string, init: RequestInit) => Promise<Response>
