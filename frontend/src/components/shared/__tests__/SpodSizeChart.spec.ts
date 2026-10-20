import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import de from '@/i18n/locales/de.json'
import type { SpodSizeMeasurementsDto } from '@/stores/shop/catalog'
import SpodSizeChart from '../SpodSizeChart.vue'

function size(name: string, values: Record<string, number>): SpodSizeMeasurementsDto {
  return {
    size: name,
    measurements: Object.entries(values).map(([letter, valueMm]) => ({ name: letter, valueMm })),
  }
}

function mountChart(props: {
  imageUrl?: string | null
  measurements: SpodSizeMeasurementsDto[]
  selectedSize?: string | null
}) {
  return mount(SpodSizeChart, {
    props: { imageUrl: null, selectedSize: null, ...props },
    global: { plugins: [createI18n({ legacy: false, locale: 'de', messages: { de } })] },
  })
}

function cells(wrapper: ReturnType<typeof mountChart>) {
  return wrapper
    .get('[data-testid="size-chart-table"]')
    .findAll('tr')
    .map((row) => row.findAll('th, td').map((cell) => cell.text()))
}

describe('SpodSizeChart', () => {
  it('lists the sizes smallest first with one column per letter, in centimetres', () => {
    const wrapper = mountChart({
      measurements: [
        size('L', { A: 740, B: 560, C: 215 }),
        size('XS', { A: 700, B: 480, C: 200 }),
        size('M', { A: 720, B: 505, C: 205 }),
      ],
    })

    expect(cells(wrapper)).toEqual([
      ['Größe', 'A', 'B', 'C'],
      ['XS', '70', '48', '20'],
      ['M', '72', '50,5', '20,5'],
      ['L', '74', '56', '21,5'],
    ])
  })

  it('shows a dash where a size lacks a letter another size has', () => {
    const wrapper = mountChart({
      measurements: [size('M', { A: 720 }), size('S', { A: 700, B: 480 })],
    })

    expect(cells(wrapper)).toEqual([
      ['Größe', 'A', 'B'],
      ['S', '70', '48'],
      ['M', '72', '–'],
    ])
  })

  it('highlights the row of the selected size', () => {
    const wrapper = mountChart({
      measurements: [size('S', { A: 700 }), size('M', { A: 720 })],
      selectedSize: 'M',
    })

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.attributes('aria-current')).toBeUndefined()
    expect(rows[1]!.attributes('aria-current')).toBe('true')
    expect(rows[1]!.classes()).toContain('bg-muted')
  })

  it('shows the picture alone when there are no measurements', () => {
    const wrapper = mountChart({ imageUrl: '/chart.webp', measurements: [] })

    expect(wrapper.get('[data-testid="size-chart-image"]').attributes('src')).toBe('/chart.webp')
    expect(wrapper.find('table').exists()).toBe(false)
  })
})
