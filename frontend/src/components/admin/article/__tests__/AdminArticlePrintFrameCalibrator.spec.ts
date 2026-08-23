import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminArticlePrintFrameCalibrator from '../AdminArticlePrintFrameCalibrator.vue'
import type { TshirtPrintFrameDto } from '@/stores/admin/tshirtArticles'

const SQUARE_FRAME: TshirtPrintFrameDto = {
  leftPct: 30,
  topPct: 25,
  widthPct: 40,
  heightPct: 40,
}

function mountCalibrator(
  props: {
    frame?: TshirtPrintFrameDto
    printAspectRatio?: '16:9' | '1:1'
    mockupUrl?: string | null
  } = {},
) {
  return mount(AdminArticlePrintFrameCalibrator, {
    props: {
      frame: props.frame ?? SQUARE_FRAME,
      printAspectRatio: props.printAspectRatio ?? '16:9',
      mockupUrl: props.mockupUrl === undefined ? '/mockup.webp' : props.mockupUrl,
    },
  })
}

/**
 * jsdom never decodes a picture, so the natural size the calibrator derives everything from is
 * stated here the way a loaded image would state it.
 */
async function reportMockupSize(
  wrapper: ReturnType<typeof mountCalibrator>,
  width: number,
  height: number,
) {
  const image = wrapper.get('[data-testid="print-frame-mockup"]').element
  Object.defineProperty(image, 'naturalWidth', { value: width, configurable: true })
  Object.defineProperty(image, 'naturalHeight', { value: height, configurable: true })
  await wrapper.get('[data-testid="print-frame-mockup"]').trigger('load')
}

describe('AdminArticlePrintFrameCalibrator', () => {
  it('derives the height from the width so the drawn frame has the print shape', async () => {
    const wrapper = mountCalibrator()
    await reportMockupSize(wrapper, 1000, 1000)

    await wrapper.get('[data-testid="print-frame-width"]').setValue('50')

    // A square mockup and a 16:9 print: 50 % of the width is as wide as 28.13 % of the height.
    expect(wrapper.emitted('update:frame')?.at(-1)?.[0]).toEqual({
      leftPct: 30,
      topPct: 25,
      widthPct: 50,
      heightPct: 28.13,
    })
  })

  it('derives the width from the height as well', async () => {
    const wrapper = mountCalibrator()
    await reportMockupSize(wrapper, 1000, 2000)

    await wrapper.get('[data-testid="print-frame-height"]').setValue('20')

    // A mockup twice as tall as it is wide: 20 % of the height is 16:9 wide at 71.11 % of the width.
    expect(wrapper.emitted('update:frame')?.at(-1)?.[0]).toMatchObject({
      heightPct: 20,
      widthPct: 71.11,
    })
  })

  it('leaves the other side alone once the ratio lock is switched off', async () => {
    const wrapper = mountCalibrator()
    await reportMockupSize(wrapper, 1000, 1000)

    await wrapper.get('#tshirt-frame-keep-ratio').trigger('click')
    await wrapper.get('[data-testid="print-frame-width"]').setValue('50')

    expect(wrapper.emitted('update:frame')?.at(-1)?.[0]).toEqual({ ...SQUARE_FRAME, widthPct: 50 })
  })

  it('warns when the stored frame is not shaped like the print', async () => {
    const wrapper = mountCalibrator()

    expect(wrapper.find('[data-testid="print-frame-ratio-warning"]').exists()).toBe(false)

    await reportMockupSize(wrapper, 1000, 1000)

    expect(wrapper.find('[data-testid="print-frame-ratio-warning"]').exists()).toBe(true)
  })

  it('is silent about the shape and cannot fit while no mockup has loaded', async () => {
    const wrapper = mountCalibrator({ mockupUrl: null })

    expect(wrapper.find('[data-testid="print-frame-no-mockup"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="print-frame-ratio-warning"]').exists()).toBe(false)
    expect(
      (wrapper.get('[data-testid="print-frame-fit"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
  })

  it('fits the height to the print ratio on demand', async () => {
    const wrapper = mountCalibrator({ printAspectRatio: '1:1' })
    await reportMockupSize(wrapper, 800, 1000)

    await wrapper.get('[data-testid="print-frame-fit"]').trigger('click')

    // A mockup of 800 × 1000: a square print that is 40 % wide is 32 % of the height tall.
    expect(wrapper.emitted('update:frame')?.at(-1)?.[0]).toMatchObject({ heightPct: 32 })
  })

  it('draws the rectangle at the four percentages the shop places the design at', () => {
    const wrapper = mountCalibrator()

    const style = wrapper.get('[data-testid="print-frame-rectangle"]').attributes('style')
    expect(style).toContain('left: 30%')
    expect(style).toContain('top: 25%')
    expect(style).toContain('width: 40%')
    expect(style).toContain('height: 40%')
  })
})
