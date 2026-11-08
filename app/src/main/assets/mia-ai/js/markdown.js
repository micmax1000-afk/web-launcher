/* Mia AI — renderer Markdown minimale e sicuro (escape di tutto l'HTML) */
(function (global) {
  'use strict';

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  }

  function inline(text) {
    // il testo è già escapato
    const codes = [];
    let t = text.replace(/`([^`\n]+)`/g, (_, c) => {
      codes.push(c);
      return '\u0000' + (codes.length - 1) + '\u0000';
    });
    t = t
      .replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
      .replace(/__([^_\n]+)__/g, '<strong>$1</strong>')
      .replace(/(^|[^*\w])\*([^*\n]+)\*(?!\w)/g, '$1<em>$2</em>')
      .replace(/~~([^~\n]+)~~/g, '<del>$1</del>');
    return t.replace(/\u0000(\d+)\u0000/g, (_, i) => '<code>' + codes[+i] + '</code>');
  }

  function render(src) {
    const lines = esc(String(src || '').replace(/\r\n?/g, '\n')).split('\n');
    const out = [];
    let para = [];
    let list = null; // { type: 'ul'|'ol', items: [] }

    function flushPara() {
      if (para.length) out.push('<p>' + inline(para.join('<br>')) + '</p>');
      para = [];
    }
    function flushList() {
      if (list) out.push('<' + list.type + '>' + list.items.map((i) => '<li>' + inline(i) + '</li>').join('') + '</' + list.type + '>');
      list = null;
    }
    function flush() {
      flushPara();
      flushList();
    }

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const fence = line.match(/^\s*```\s*([\w+#.-]*)\s*$/);
      if (fence) {
        flush();
        const buf = [];
        i++;
        while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) buf.push(lines[i++]);
        out.push(
          '<div class="code"><div class="code-head"><span>' +
            (fence[1] || 'codice') +
            '</span><button type="button" class="copy-code">Copia</button></div><pre><code>' +
            buf.join('\n') +
            '</code></pre></div>'
        );
        continue;
      }
      let m;
      if (!line.trim()) {
        flush();
      } else if ((m = line.match(/^(#{1,6})\s+(.*)$/))) {
        flush();
        const lvl = Math.min(m[1].length + 2, 6);
        out.push('<h' + lvl + '>' + inline(m[2]) + '</h' + lvl + '>');
      } else if (/^\s*([-*_])\s*\1\s*\1[\s\-*_]*$/.test(line)) {
        flush();
        out.push('<hr>');
      } else if ((m = line.match(/^\s*&gt;\s?(.*)$/))) {
        flush();
        out.push('<blockquote>' + inline(m[1]) + '</blockquote>');
      } else if ((m = line.match(/^\s*[-*+]\s+(.*)$/))) {
        flushPara();
        if (!list || list.type !== 'ul') {
          flushList();
          list = { type: 'ul', items: [] };
        }
        list.items.push(m[1]);
      } else if ((m = line.match(/^\s*\d+[.)]\s+(.*)$/))) {
        flushPara();
        if (!list || list.type !== 'ol') {
          flushList();
          list = { type: 'ol', items: [] };
        }
        list.items.push(m[1]);
      } else if (list && /^\s{2,}\S/.test(line)) {
        list.items[list.items.length - 1] += ' ' + line.trim();
      } else {
        flushList();
        para.push(line);
      }
    }
    flush();
    return out.join('');
  }

  global.MiaMarkdown = { render, esc };
})(window);
