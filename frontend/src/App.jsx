import { useState, useEffect, useRef } from 'react'
import ChatMessage from './ChatMessage'
import InputBar from './InputBar'
import ChartModal from './ChartModal'
import './App.css'

let nextId = 1

// Cumulative progress percentage at start and end of each step.
// Weights: steps 1-3 = 1% each, step 4 = 70%, step 5 = 1%, step 6 = 21%.
const STEP_PCT = {
  1: [0,  1],
  2: [1,  2],
  3: [2,  3],
  4: [3,  73],
  5: [73, 74],
  6: [74, 95],
}

function base64ToBlob(base64, mimeType) {
  const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0))
  return new Blob([bytes], { type: mimeType })
}

export default function App() {
  const [messages, setMessages] = useState([])
  const [isLoading, setIsLoading] = useState(false)
  const [selectedImage, setSelectedImage] = useState(null)
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  function handleClear() {
    if (!window.confirm('Clear all messages?')) return
    messages.forEach(m => { if (m.imageUrl) URL.revokeObjectURL(m.imageUrl) })
    setMessages([])
    setSelectedImage(null)
  }

  async function handleSubmit(prompt) {
    const userId = nextId++
    const assistantId = nextId++
    const startTime = Date.now()

    setMessages(prev => [...prev,
      { id: userId, role: 'user', text: prompt },
      {
        id: assistantId,
        role: 'assistant',
        status: 'loading',
        startTime,
        currentStep: 1,
        currentStepName: 'Parsing date range',
        pct: 0,
        imageUrl: null,
        errorMsg: null,
      },
    ])
    setIsLoading(true)

    // Crawl the progress bar within long-running steps so it doesn't sit frozen.
    // Caps at 95% of each step's range so the real step-end event causes a
    // visible jump when it arrives.
    let crawlInterval = null
    const clearCrawl = () => {
      if (crawlInterval) { clearInterval(crawlInterval); crawlInterval = null }
    }
    const startCrawl = (pctStart, pctEnd) => {
      clearCrawl()
      const cap = pctStart + (pctEnd - pctStart) * 0.95
      crawlInterval = setInterval(() => {
        setMessages(prev => prev.map(m => {
          if (m.id !== assistantId || m.status !== 'loading') return m
          return { ...m, pct: m.pct + (cap - m.pct) * 0.015 }
        }))
      }, 100)
    }

    try {
      const res = await fetch('/api/ask/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt }),
      })

      if (!res.ok || !res.body) {
        const text = await res.text().catch(() => res.statusText)
        setMessages(prev => prev.map(m =>
          m.id === assistantId
            ? { ...m, status: 'error', errorMsg: `${res.status} — ${text}` }
            : m
        ))
        setIsLoading(false)
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        // SSE events are separated by double newline
        const parts = buffer.split('\n\n')
        buffer = parts.pop()  // keep the incomplete trailing chunk

        for (const part of parts) {
          const lines = part.split('\n')
          const eventLine = lines.find(l => l.startsWith('event:'))
          const dataLine  = lines.find(l => l.startsWith('data:'))
          if (!eventLine || !dataLine) continue

          const eventType = eventLine.slice('event:'.length).trim()
          let data
          try { data = JSON.parse(dataLine.slice('data:'.length).trim()) }
          catch { continue }

          if (eventType === 'step-start') {
            const { step, name } = data
            const [pctStart, pctEnd] = STEP_PCT[step] ?? [0, 0]
            clearCrawl()
            setMessages(prev => prev.map(m =>
              m.id === assistantId
                ? { ...m, currentStep: step, currentStepName: name, pct: pctStart }
                : m
            ))
            // Only crawl for steps that take seconds (4 = Ollama ~18s, 6 = sandbox ~6s)
            if (step === 4 || step === 6) startCrawl(pctStart, pctEnd)
          }

          else if (eventType === 'step-end') {
            const [, pctEnd] = STEP_PCT[data.step] ?? [0, 0]
            clearCrawl()
            setMessages(prev => prev.map(m =>
              m.id === assistantId ? { ...m, pct: pctEnd } : m
            ))
          }

          else if (eventType === 'complete') {
            clearCrawl()
            const { chartBase64, totalDurationMs } = data
            const imageUrl = URL.createObjectURL(base64ToBlob(chartBase64, 'image/png'))
            const completedAt = Date.now()
            setMessages(prev => prev.map(m =>
              m.id === assistantId
                ? { ...m, status: 'completing', pct: 100, imageUrl, completedAt, durationMs: totalDurationMs }
                : m
            ))
            setTimeout(() => {
              setMessages(prev => prev.map(m =>
                m.id === assistantId ? { ...m, status: 'done' } : m
              ))
              setIsLoading(false)
            }, 300)
          }

          else if (eventType === 'error') {
            clearCrawl()
            const { step, name, message: errMsg } = data
            const label = step ? `Step ${step} (${name}): ${errMsg}` : errMsg
            setMessages(prev => prev.map(m =>
              m.id === assistantId
                ? { ...m, status: 'error', errorMsg: label }
                : m
            ))
            setIsLoading(false)
          }
        }
      }

    } catch (err) {
      clearCrawl()
      setMessages(prev => prev.map(m =>
        m.id === assistantId
          ? { ...m, status: 'error', errorMsg: err.message }
          : m
      ))
      setIsLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <span className="app-title">Chart Generator</span>
      </header>

      <div className="conversation">
        {messages.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon">📊</div>
            <p className="empty-hint">
              Try: <em>"Plot bar chart from Jan 2026 to March 2026"</em>
            </p>
            <p className="empty-sub">Dynamic Chart Generation (Bar, Pie, etc.) in a Secure Sandbox Environment.</p>
          </div>
        )}
        {messages.map(msg => (
          <ChatMessage
            key={msg.id}
            message={msg}
            onImageClick={setSelectedImage}
          />
        ))}
        <div ref={bottomRef} />
      </div>

      <InputBar onSubmit={handleSubmit} onClear={handleClear} disabled={isLoading} isEmpty={messages.length === 0} />

      {selectedImage && (
        <ChartModal imageUrl={selectedImage} onClose={() => setSelectedImage(null)} />
      )}
    </div>
  )
}
