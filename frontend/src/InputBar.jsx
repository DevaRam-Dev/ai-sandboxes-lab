import { useState, useRef, useEffect } from 'react'

export default function InputBar({ onSubmit, onClear, disabled, isEmpty }) {
  const [text, setText] = useState('')
  const textareaRef = useRef(null)

  useEffect(() => {
    const ta = textareaRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px'
  }, [text])

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  function submit() {
    const trimmed = text.trim()
    if (!trimmed || disabled) return
    onSubmit(trimmed)
    setText('')
  }

  return (
    <div className="input-bar">
      <button
        className="clear-btn"
        onClick={onClear}
        disabled={disabled || isEmpty}
      >
        Clear
      </button>
      <textarea
        ref={textareaRef}
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Describe a chart to generate…"
        rows={1}
        disabled={disabled}
      />
      <button
        className="send-btn"
        onClick={submit}
        disabled={disabled || !text.trim()}
      >
        Send
      </button>
    </div>
  )
}
