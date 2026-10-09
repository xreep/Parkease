/** Ids that tie a tab to its panel: the panel gets `id={panelId(p, v)}` and `aria-labelledby={tabId(p, v)}`. */
export const tabId = (prefix: string, value: string) => `${prefix}-tab-${value}`
export const panelId = (prefix: string, value: string) => `${prefix}-panel-${value}`
