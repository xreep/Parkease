import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorPage } from '../pages/ErrorPage'

type Props = {
  children: ReactNode
  /** When this changes (the route, say) a boundary that has caught an error tries its children again. */
  resetKey?: string
}

/** Catches errors thrown while rendering below it and shows the friendly error page instead of a blank screen. */
export class ErrorBoundary extends Component<Props, { failed: boolean; resetKey?: string }> {
  state = { failed: false, resetKey: this.props.resetKey }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  static getDerivedStateFromProps(props: Props, state: { failed: boolean; resetKey?: string }) {
    return props.resetKey === state.resetKey ? null : { failed: false, resetKey: props.resetKey }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error', error, info.componentStack)
  }

  render() {
    return this.state.failed ? <ErrorPage /> : this.props.children
  }
}
