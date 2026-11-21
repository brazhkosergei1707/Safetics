/**
 * Текст із мапи перекладів, що приходить із сервера ({"en": "Drill hole", "uk": "Свердловина"}):
 * поточна мова → англійська → перша, яка є.
 */
export function localized(labels: Record<string, string> | undefined, language: string): string {
  if (!labels) return ''
  return labels[language] ?? labels.en ?? Object.values(labels)[0] ?? ''
}
