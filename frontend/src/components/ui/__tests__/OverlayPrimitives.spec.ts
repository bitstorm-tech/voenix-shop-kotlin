import { mount } from '@vue/test-utils'
import { shallowRef } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import {
  NavigationMenu,
  NavigationMenuItem,
  NavigationMenuList,
  NavigationMenuTrigger,
} from '@/components/ui/navigation-menu'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('overlay and disclosure UI primitives', () => {
  it('opens and closes alert dialog content through the wrapped Reka behavior', async () => {
    const wrapper = mount(
      {
        components: {
          AlertDialog,
          AlertDialogAction,
          AlertDialogCancel,
          AlertDialogContent,
          AlertDialogDescription,
          AlertDialogFooter,
          AlertDialogHeader,
          AlertDialogTitle,
          AlertDialogTrigger,
        },
        setup() {
          const open = shallowRef(false)

          return {
            open,
          }
        },
        template: `
          <AlertDialog v-model:open="open">
            <AlertDialogTrigger>Delete article</AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete this article?</AlertDialogTitle>
                <AlertDialogDescription>This cannot be undone.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction>Delete</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        `,
      },
      {
        attachTo: document.body,
      },
    )

    await wrapper.get('button').trigger('click')

    expect(wrapper.vm.open).toBe(true)
    expect(document.body.textContent).toContain('Delete this article?')

    const cancel = Array.from(document.body.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Cancel'),
    )

    cancel?.click()
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.open).toBe(false)
  })

  it('renders a hover card trigger and shows the portal content when opened', async () => {
    const open = shallowRef(false)
    const wrapper = mount(
      {
        components: {
          HoverCard,
          HoverCardContent,
          HoverCardTrigger,
        },
        setup() {
          return {
            open,
          }
        },
        template: `
          <HoverCard v-model:open="open">
            <HoverCardTrigger as-child>
              <img src="/thumbnail.png" alt="Prompt thumbnail" />
            </HoverCardTrigger>
            <HoverCardContent>Larger preview</HoverCardContent>
          </HoverCard>
        `,
      },
      {
        attachTo: document.body,
      },
    )

    expect(wrapper.get('img').attributes('alt')).toBe('Prompt thumbnail')
    expect(document.body.textContent).not.toContain('Larger preview')

    open.value = true
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(document.body.textContent).toContain('Larger preview')
  })

  it('opens a side sheet with side-specific panel classes', async () => {
    const wrapper = mount(
      {
        components: {
          Sheet,
          SheetContent,
          SheetDescription,
          SheetHeader,
          SheetTitle,
          SheetTrigger,
        },
        template: `
          <Sheet>
            <SheetTrigger>Open navigation</SheetTrigger>
            <SheetContent side="left">
              <SheetHeader>
                <SheetTitle>Navigation</SheetTitle>
                <SheetDescription>Admin navigation links</SheetDescription>
              </SheetHeader>
            </SheetContent>
          </Sheet>
        `,
      },
      {
        attachTo: document.body,
      },
    )

    await wrapper.get('button').trigger('click')

    const dialog = document.body.querySelector('[role="dialog"]')

    expect(dialog?.textContent).toContain('Navigation')
    expect(dialog?.className).toContain('left-0')
  })

  it('renders plain navigation menu triggers without button chrome', () => {
    const wrapper = mount({
      components: {
        NavigationMenu,
        NavigationMenuItem,
        NavigationMenuList,
        NavigationMenuTrigger,
      },
      template: `
        <NavigationMenu>
          <NavigationMenuList>
            <NavigationMenuItem>
              <NavigationMenuTrigger variant="plain">Tassen</NavigationMenuTrigger>
            </NavigationMenuItem>
          </NavigationMenuList>
        </NavigationMenu>
      `,
    })

    const className = wrapper.get('button').attributes('class') ?? ''

    expect(className).toContain('bg-transparent')
    expect(className).toContain('rounded-none')
    expect(className).toContain('shadow-none')
    expect(className).toContain('hover:bg-transparent')
    expect(className).toContain('data-[state=open]:bg-transparent')
    expect(className).not.toContain('bg-background')
    expect(className).not.toContain('rounded-md')
    expect(className).not.toContain('hover:bg-accent')
    expect(className).not.toContain('data-[state=open]:bg-accent')
  })

  it('switches visible tab content via wrapped tab triggers', async () => {
    const wrapper = mount({
      components: {
        Tabs,
        TabsContent,
        TabsList,
        TabsTrigger,
      },
      template: `
        <Tabs default-value="details">
          <TabsList>
            <TabsTrigger value="details">Details</TabsTrigger>
            <TabsTrigger value="pricing">Pricing</TabsTrigger>
          </TabsList>
          <TabsContent value="details">Details panel</TabsContent>
          <TabsContent value="pricing">Pricing panel</TabsContent>
        </Tabs>
      `,
    })

    expect(wrapper.text()).toContain('Details panel')
    expect(wrapper.text()).not.toContain('Pricing panel')

    const pricingTab = wrapper.findAll('[role="tab"]')[1]

    if (!pricingTab) {
      throw new Error('Pricing tab was not rendered')
    }

    await pricingTab.trigger('mousedown', {
      button: 0,
      ctrlKey: false,
    })

    expect(wrapper.text()).toContain('Pricing panel')
    expect(wrapper.text()).not.toContain('Details panel')
  })
})
