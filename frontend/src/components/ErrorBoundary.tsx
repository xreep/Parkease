import { Component, type ErrorInfo, type ReactNode } from 'react'
import { isChunkLoadError, reloadForNewVersion } from '../lib/chunkError'
import { ErrorPage } from '../pages/ErrorPage'

type Props = {
  children: ReactNode
  /** When this changes (the route, say) a boundary that has caught an error tries its children again. */
  resetKey?: string
}
type State = { failed: boolean; stale: boolean; resetKey?: string }

/**
 * Catches errors thrown while rendering below it and shows the friendly error page instead of a blank screen.
 * A failed route chunk means the tab runs an old build: it reloads once by itself, and otherwise says a new version
 * is available.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, stale: false, resetKey: this.props.resetKey }

  static getDerivedStateFromError(error: unknown): Partial<State> {
    return { failed: true, stale: isChunkLoadError(error) }
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.resetKey === state.resetKey ? null : { failed: false, stale: false, resetKey: props.resetKey }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error', error, info.componentStack)
    if (isChunkLoadError(error)) reloadForNewVersion()
  }

  render() {
    return this.state.failed ? <ErrorPage stale={this.state.stale} /> : this.props.children
  }
}
