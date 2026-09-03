import { AxeBuilder } from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

export async function expectNoSeriousAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze()
  const blocking = results.violations.filter((violation) =>
    ['serious', 'critical'].includes(violation.impact ?? ''),
  )
  const lowerImpact = results.violations.filter(
    (violation) => !['serious', 'critical'].includes(violation.impact ?? ''),
  )
  if (lowerImpact.length) {
    const description = lowerImpact
      .map((violation) => `${violation.id}: ${violation.help}`)
      .join('\n')
    test.info().annotations.push({ type: 'axe-lower-impact', description })
    console.warn(`axe lower-impact findings:\n${description}`)
  }
  expect(blocking, `serious/critical axe violations: ${JSON.stringify(blocking)}`).toEqual([])
}

export async function expectNoHorizontalPageOverflow(page: Page) {
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true)
}
