import { Marked, type Tokens } from 'marked'
import DOMPurify from 'dompurify'

const productPath = /^\/products\/[1-9]\d{0,8}$/
const escape = (text: string) =>
  text.replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        char
      ]!,
  )
const productLink = (href: string, label: string) =>
  `<a class="advisor-product-link" href="${href}">${label}</a>`

// Parse Markdown with an established parser, then keep a small display-only HTML subset.
// Model HTML and images remain text; generated links can only open a local product route.
const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    html(token: Tokens.HTML | Tokens.Tag) {
      return escape(token.text)
    },
    image(token: Tokens.Image) {
      return escape(token.text)
    },
    link(token: Tokens.Link) {
      const label = this.parser.parseInline(token.tokens)
      return productPath.test(token.href)
        ? productLink(token.href, label)
        : label
    },
  },
  extensions: [
    {
      name: 'productPath',
      level: 'inline',
      start(source) {
        return source.indexOf('/products/')
      },
      tokenizer(source) {
        const match = /^\/products\/[1-9]\d{0,8}(?![\w/?#-])/.exec(source)
        if (match) return { type: 'productPath', raw: match[0], text: match[0] }
      },
      renderer(token) {
        return productLink(token.text, '查看商品')
      },
    },
  ],
})

export function renderAdvisorMarkdown(source: string, links = true): string {
  return DOMPurify.sanitize(markdown.parse(source, { async: false }), {
    ALLOWED_TAGS: [
      'p',
      'br',
      'strong',
      'em',
      'del',
      'ul',
      'ol',
      'li',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'blockquote',
      'pre',
      'code',
      'hr',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
      'a',
    ],
    ALLOWED_ATTR: ['href', 'class', 'start'],
    FORBID_TAGS: links ? [] : ['a'],
    ALLOW_DATA_ATTR: false,
    ALLOWED_URI_REGEXP: productPath,
  })
}
