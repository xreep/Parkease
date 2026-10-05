/**
 * Opens a lazily fetched URL in a new tab without tripping the popup blocker: the tab is opened
 * synchronously inside the click handler, then pointed at the URL once the request resolves.
 * Rejects (after closing the blank tab) when the request fails or pop-ups are blocked.
 */
export async function openInNewTab(getUrl: () => Promise<{ url: string }>): Promise<void> {
  const tab = window.open('about:blank', '_blank')
  if (!tab) throw new Error('Allow pop-ups for this site to view the document.')
  try {
    const { url } = await getUrl()
    tab.opener = null
    tab.location.href = url
  } catch (error) {
    tab.close()
    throw error
  }
}
