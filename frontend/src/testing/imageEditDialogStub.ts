import { defineComponent, watch, type PropType } from 'vue'

/**
 * Stands in for `AdminImageEditDialog` (canvas and image decoding do not exist in jsdom): it records
 * the source of every opening and, while `autoApply` is on, applies a chosen file unchanged, which
 * is what an admin clicking "Apply" without editing does. Reset it in `beforeEach`.
 */
export const imageEditDialogStub = {
  openings: [] as (Blob | string | null)[],
  autoApply: true,
  reset() {
    this.openings = []
    this.autoApply = true
  },
  component: defineComponent({
    props: {
      open: { type: Boolean, required: true },
      source: { type: [Object, String] as PropType<Blob | string | null>, default: null },
    },
    emits: ['apply', 'update:open'],
    setup(props, { emit }) {
      watch(
        () => props.open,
        (open) => {
          if (!open) return
          imageEditDialogStub.openings.push(props.source)
          if (imageEditDialogStub.autoApply && props.source instanceof File) {
            emit('apply', props.source)
          }
          emit('update:open', false)
        },
      )
      return () => null
    },
  }),
}
