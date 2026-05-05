import { useState, useEffect } from 'react'

export default function ChatMessage({ message, onImageClick }) {
  if (message.role === 'user') {
    return (
      <div className="turn">
        <div className="user-bubble">{message.text}</div>
      </div>
    )
  }
  return (
    <div className="turn">
      <AssistantResponse message={message} onImageClick={onImageClick} />
    </div>
  )
}

function AssistantResponse({ message, onImageClick }) {
  const [elapsedSec, setElapsedSec] = useState(0)

  // Tick elapsed seconds while request is in flight
  useEffect(() => {
    if (message.status !== 'loading' && message.status !== 'completing') return
    const interval = setInterval(() => {
      setElapsedSec(Math.floor((Date.now() - message.startTime) / 1000))
    }, 1000)
    return () => clearInterval(interval)
  }, [message.status, message.startTime])

  useEffect(() => {
    const url = message.imageUrl
    return () => { if (url) URL.revokeObjectURL(url) }
  }, [message.imageUrl])

  if (message.status === 'loading') {
    return (
      <LoadingUI
        stepNum={message.currentStep ?? 1}
        stepName={message.currentStepName ?? '…'}
        pct={message.pct ?? 0}
        elapsedSec={elapsedSec}
      />
    )
  }

  if (message.status === 'completing') {
    return (
      <LoadingUI
        stepNum={6}
        stepName="Running in sandbox"
        pct={100}
        elapsedSec={elapsedSec}
      />
    )
  }

  if (message.status === 'error') {
    return (
      <div className="assistant-error">
        <span className="error-label">Error</span>
        {message.errorMsg}
      </div>
    )
  }

  // status === 'done'
  const generatedIn = (message.durationMs / 1000).toFixed(1)
  const generatedAt = new Date(message.completedAt).toLocaleString()
  return (
    <div className="chart-block">
      <img
        className="chart-img"
        src={message.imageUrl}
        alt="Generated chart"
        onClick={() => onImageClick(message.imageUrl)}
      />
      <div className="chart-meta">
        <span>Generated in: {generatedIn}s</span>
        <span>Generated at: {generatedAt}</span>
      </div>
    </div>
  )
}

function LoadingUI({ stepNum, stepName, pct, elapsedSec }) {
  const displayPct = Math.min(100, Math.max(0, pct))
  return (
    <div className="loading-block">
      <div className="step-row">
        <span className="step-counter">
          Step {stepNum}/6 — <span className="step-name">{stepName}</span>
        </span>
        <span className="step-elapsed">{elapsedSec}s</span>
      </div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${displayPct}%` }} />
      </div>
    </div>
  )
}
