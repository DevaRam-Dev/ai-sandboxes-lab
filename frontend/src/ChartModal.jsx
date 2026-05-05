import { useEffect } from 'react'

export default function ChartModal({ imageUrl, onClose }) {
  useEffect(() => {
    function handleKey(e) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <img
        className="modal-img"
        src={imageUrl}
        alt="Chart full size"
        onClick={e => e.stopPropagation()}
      />
    </div>
  )
}
