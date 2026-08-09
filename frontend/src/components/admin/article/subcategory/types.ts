/**
 * What the subcategory dialog emits when the form is saved.
 *
 * The example image is split in two: a newly chosen `exampleImage` still has to be pre-uploaded
 * before the subcategory is written, while `exampleImageFilename` is the already stored name the
 * user kept. `null` in both fields means the subcategory has no example image — that is how an
 * existing one is removed. The dialog itself never talks to the API, so the upload happens where
 * the store is used.
 */
export interface AdminArticleSubcategoryFormPayload {
  categoryId: number
  name: string
  description: string | null
  active: boolean
  exampleImage: File | null
  exampleImageFilename: string | null
}
