// This block is embedded in both extensions; register once on the shared extension API.
if (!pi[Symbol.for('magicpaper.screenshotContext')]) {
  pi[Symbol.for('magicpaper.screenshotContext')] = true;
  pi.on('context', async (event) => {
    const names = new Set(['computer', 'application', 'magicpaper_browser_screenshot']);
    const screenshots = event.messages.filter(message => message.role === 'toolResult' &&
      names.has(message.toolName) && Array.isArray(message.content) && message.content.some(part => part.type === 'image'));
    const keep = screenshots.at(-1);
    return { messages: event.messages.map(message => {
      if (message === keep || !screenshots.includes(message)) return message;
      return { ...message, content: message.content.map(part => part.type === 'image'
        ? { type: 'text', text: '[Earlier screenshot image omitted from model context. Original tool metadata is retained. Use a fresh screenshot before acting.]' }
        : part) };
    }) };
  });
}
