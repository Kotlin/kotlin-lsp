/**
 * The model and the HTML of the `intellij/editOptions` form. This module has no `vscode` import, so the tests run it in
 * plain Node.
 *
 * The server sends the components of an IntelliJ `OptPane` (`OptionComponentData` on the server), the value of each
 * control by `bindId`, and every user-visible string. The form renders the components with the theme colors of VS Code,
 * and the webview script collects the values when the user submits the form.
 */

export type OptionComponent =
  | CheckboxComponent
  | {
      kind: 'string';
      bindId: string;
      prefix: string;
      suffix: string;
      width?: number;
      description?: string | null;
    }
  | {
      kind: 'expandableString';
      bindId: string;
      label: string;
      separator: string;
      description?: string | null;
    }
  | {
      kind: 'number';
      bindId: string;
      prefix: string;
      suffix: string;
      min: number;
      max: number;
      integer?: boolean;
      description?: string | null;
    }
  | {
      kind: 'dropdown';
      bindId: string;
      prefix: string;
      suffix: string;
      options: { key: string; label: string }[];
    }
  | { kind: 'stringList'; bindId: string; label: string; description?: string | null }
  | {
      kind: 'table';
      label: string;
      columns: { bindId: string; name: string }[];
      description?: string | null;
    }
  | {
      kind: 'multiSelector';
      bindId: string;
      elements: { text: string; secondaryText?: string | null }[];
      multiple: boolean;
      allowsEmpty: boolean;
    }
  | { kind: 'group'; label: string; children: OptionComponent[] }
  | { kind: 'horizontalStack'; children: OptionComponent[] }
  | { kind: 'separator'; label?: string | null }
  | { kind: 'settingLink'; label: string }
  | { kind: 'tabSet'; tabs: { label: string; children: OptionComponent[] }[] }
  // The server sends the checkboxes of a panel without their `kind`.
  | { kind: 'checkboxPanel'; checkboxes: Omit<CheckboxComponent, 'kind'>[] };

type CheckboxComponent = {
  kind: 'checkbox';
  bindId: string;
  label: string;
  children?: OptionComponent[];
  description?: string | null;
};

/** The value of one option. A list holds strings, or the indexes of the selected elements of a multi selector. */
export type OptionValue = boolean | number | string | string[] | number[];

export type EditOptionsParams = {
  title: string;
  components: OptionComponent[];
  values: Record<string, unknown>;
  errors?: Record<string, string>;
  okLabel: string;
  cancelLabel: string;
  addLabel: string;
  removeLabel: string;
};

/** `values` is absent when the user cancelled the form. */
export type EditOptionsResult = { values?: Record<string, OptionValue> };

/** A message from the webview script to the extension. */
export type EditOptionsMessage =
  | { type: 'submit'; values: Record<string, OptionValue> }
  | { type: 'cancel' };

/** Narrows a message of the webview, which is external input. */
export function parseEditOptionsMessage(message: unknown): EditOptionsMessage | undefined {
  if (typeof message !== 'object' || message === null) return undefined;
  const { type, values } = message as { type?: unknown; values?: unknown };
  if (type === 'cancel') return { type };
  if (type !== 'submit' || typeof values !== 'object' || values === null) return undefined;

  const result: Record<string, OptionValue> = {};
  for (const [bindId, value] of Object.entries(values)) {
    if (isOptionValue(value)) result[bindId] = value;
  }
  return { type, values: result };
}

function isOptionValue(value: unknown): value is OptionValue {
  if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string')
    return true;
  if (!Array.isArray(value)) return false;
  return (
    value.every((item) => typeof item === 'string') ||
    value.every((item) => typeof item === 'number')
  );
}

export type RenderOptions = {
  /** The nonce of the inline style and script, which the content security policy allows. */
  nonce: string;
};

/**
 * The HTML document of the form.
 *
 * A description is HTML from the server bundles, so it is inserted as it is. The content security policy allows
 * only the inline style and script with [RenderOptions.nonce], so a script or a remote resource in a description
 * does not run.
 */
export function renderEditOptionsHtml(params: EditOptionsParams, options: RenderOptions): string {
  const renderer = new FormRenderer(params);
  const body = renderer.components(params.components);
  const nonce = escapeHtml(options.nonce);
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(params.title)}</title>
<style nonce="${nonce}">${FORM_STYLE}</style>
</head>
<body>
<h2>${escapeHtml(params.title)}</h2>
<form id="form" novalidate>
${body}
<div class="buttons">
<button type="submit" class="primary">${escapeHtml(params.okLabel)}</button>
<button type="button" id="cancel" class="secondary">${escapeHtml(params.cancelLabel)}</button>
</div>
</form>
<script nonce="${nonce}">${FORM_SCRIPT}</script>
</body>
</html>`;
}

export function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/**
 * Renders the components the way `UiDslOptPaneRenderer` lays them out in the IDE: one control per row, a split label
 * around its control, and the children of a checkbox indented under it. A clear checkbox disables its children.
 */
class FormRenderer {
  private nextId = 0;

  constructor(private readonly params: EditOptionsParams) {}

  components(components: OptionComponent[]): string {
    return components.map((component) => this.component(component)).join('\n');
  }

  private component(component: OptionComponent): string {
    switch (component.kind) {
      case 'checkbox':
        return this.checkbox(component);
      case 'string': {
        const id = this.id();
        const width =
          component.width !== undefined && component.width > 0 ? ` size="${component.width}"` : '';
        const input = `<input type="text" id="${id}"${this.bind(component.bindId, 'string')}${width} value="${escapeHtml(this.string(component.bindId))}"${this.invalid(component.bindId)}>`;
        return this.row(
          id,
          component.prefix,
          input,
          component.suffix,
          component.bindId,
          component.description,
        );
      }
      case 'expandableString': {
        const id = this.id();
        const lines = this.string(component.bindId).split(component.separator).join('\n');
        const textarea = `<textarea id="${id}" rows="3"${this.bind(component.bindId, 'expandableString')} data-separator="${escapeHtml(component.separator)}"${this.invalid(component.bindId)}>${escapeHtml(lines)}</textarea>`;
        return this.block(id, component.label, textarea, component.bindId, component.description);
      }
      case 'number': {
        const id = this.id();
        const step = component.integer === false ? 'any' : '1';
        const value = this.params.values[component.bindId];
        const text = typeof value === 'number' ? String(value) : '';
        const input = `<input type="number" id="${id}"${this.bind(component.bindId, 'number')} min="${component.min}" max="${component.max}" step="${step}" value="${escapeHtml(text)}"${this.invalid(component.bindId)}>`;
        return this.row(
          id,
          component.prefix,
          input,
          component.suffix,
          component.bindId,
          component.description,
        );
      }
      case 'dropdown': {
        const id = this.id();
        const selected = this.string(component.bindId);
        const options = component.options
          .map((option) => {
            const attribute = option.key === selected ? ' selected' : '';
            return `<option value="${escapeHtml(option.key)}"${attribute}>${escapeHtml(option.label)}</option>`;
          })
          .join('');
        const select = `<select id="${id}"${this.bind(component.bindId, 'dropdown')}${this.invalid(component.bindId)}>${options}</select>`;
        return this.row(id, component.prefix, select, component.suffix, component.bindId);
      }
      case 'stringList':
        return this.table(
          component.label,
          [{ bindId: component.bindId, name: '' }],
          component.description,
        );
      case 'table':
        return this.table(component.label, component.columns, component.description);
      case 'multiSelector':
        return this.multiSelector(component);
      case 'group':
        return `<fieldset><legend>${escapeHtml(component.label)}</legend>\n${this.components(component.children)}\n</fieldset>`;
      case 'horizontalStack':
        return `<div class="stack">\n${this.components(component.children)}\n</div>`;
      case 'separator':
        return component.label
          ? `<div class="separator"><span>${escapeHtml(component.label)}</span></div>`
          : '<hr>';
      case 'settingLink':
        // The client cannot open the settings of the IDE, so the link is plain text.
        return `<div class="row">${escapeHtml(component.label)}</div>`;
      case 'tabSet':
        return this.tabSet(component.tabs);
      case 'checkboxPanel':
        return component.checkboxes
          .map((checkbox) => this.checkbox({ ...checkbox, kind: 'checkbox' }))
          .join('\n');
    }
  }

  private checkbox(component: CheckboxComponent): string {
    const id = this.id();
    const checked = this.params.values[component.bindId] === true ? ' checked' : '';
    const lines = [
      `<div class="row"><input type="checkbox" id="${id}"${this.bind(component.bindId, 'checkbox')}${checked}>` +
        `<label for="${id}">${escapeHtml(component.label)}</label></div>`,
      this.description(component.description),
      this.error(component.bindId),
    ];
    const children = component.children ?? [];
    if (children.length > 0) {
      lines.push(
        `<div class="children" data-parent="${id}">\n${this.components(children)}\n</div>`,
      );
    }
    return lines.filter((line) => line !== '').join('\n');
  }

  private table(
    label: string,
    columns: { bindId: string; name: string }[],
    description?: string | null,
  ): string {
    const cells = columns.map((column) => this.list(column.bindId));
    const rowCount = Math.max(0, ...cells.map((list) => list.length));
    const header = columns.some((column) => column.name !== '')
      ? `<div class="table-row header">${columns.map((column) => `<span>${escapeHtml(column.name)}</span>`).join('')}<span></span></div>`
      : '';
    const rows: string[] = [];
    for (let row = 0; row < rowCount; row++) {
      rows.push(this.tableRow(cells.map((list) => list[row] ?? '')));
    }
    const bindIds = escapeHtml(JSON.stringify(columns.map((column) => column.bindId)));
    const errors = columns.map((column) => this.error(column.bindId)).filter((line) => line !== '');
    return [
      label !== '' ? `<div class="block-label">${escapeHtml(label)}</div>` : '',
      `<div class="table" data-kind="table" data-columns="${bindIds}">`,
      header,
      `<div class="table-rows">${rows.join('')}</div>`,
      `<template>${this.tableRow(columns.map(() => ''))}</template>`,
      `<button type="button" class="secondary add-row">${escapeHtml(this.params.addLabel)}</button>`,
      '</div>',
      this.description(description),
      ...errors,
    ]
      .filter((line) => line !== '')
      .join('\n');
  }

  private tableRow(cells: string[]): string {
    const inputs = cells.map((cell) => `<input type="text" value="${escapeHtml(cell)}">`).join('');
    return `<div class="table-row">${inputs}<button type="button" class="secondary remove-row">${escapeHtml(this.params.removeLabel)}</button></div>`;
  }

  /**
   * Renders a list box, as the list of `MultiSelector` in the IDE. The size is two rows at least, because a select of one
   * row is a dropdown. An option holds only text, so the secondary text follows the text in parentheses.
   */
  private multiSelector(component: Extract<OptionComponent, { kind: 'multiSelector' }>): string {
    const selected = new Set(this.list(component.bindId).map(Number));
    const options = component.elements.map((element, index) => {
      const attribute = selected.has(index) ? ' selected' : '';
      const text = element.secondaryText
        ? `${element.text} (${element.secondaryText})`
        : element.text;
      return `<option value="${index}"${attribute}>${escapeHtml(text)}</option>`;
    });
    const size = Math.min(Math.max(component.elements.length, 2), 10);
    const multiple = component.multiple ? ' multiple' : '';
    return [
      `<select class="selector" size="${size}"${multiple}${this.bind(component.bindId, 'multiSelector')}${this.invalid(component.bindId)}>${options.join('')}</select>`,
      this.error(component.bindId),
    ]
      .filter((line) => line !== '')
      .join('\n');
  }

  private tabSet(tabs: { label: string; children: OptionComponent[] }[]): string {
    const headers = tabs
      .map((tab, index) => {
        const selected = index === 0 ? ' selected' : '';
        return `<button type="button" class="tab${selected}" data-tab="${index}">${escapeHtml(tab.label)}</button>`;
      })
      .join('');
    const panels = tabs
      .map((tab, index) => {
        const hidden = index === 0 ? '' : ' hidden';
        return `<div class="tab-panel" data-tab-panel="${index}"${hidden}>\n${this.components(tab.children)}\n</div>`;
      })
      .join('\n');
    return `<div class="tab-set">\n<div class="tab-headers">${headers}</div>\n${panels}\n</div>`;
  }

  private row(
    id: string,
    prefix: string,
    control: string,
    suffix: string,
    bindId: string,
    description?: string | null,
  ): string {
    const label = prefix.trim() !== '' ? `<label for="${id}">${escapeHtml(prefix)}</label>` : '';
    const after = suffix.trim() !== '' ? `<span>${escapeHtml(suffix)}</span>` : '';
    return [
      `<div class="row">${label}${control}${after}</div>`,
      this.description(description),
      this.error(bindId),
    ]
      .filter((line) => line !== '')
      .join('\n');
  }

  private block(
    id: string,
    label: string,
    control: string,
    bindId: string,
    description?: string | null,
  ): string {
    const header =
      label !== '' ? `<label class="block-label" for="${id}">${escapeHtml(label)}</label>` : '';
    return [header, control, this.description(description), this.error(bindId)]
      .filter((line) => line !== '')
      .join('\n');
  }

  private description(description?: string | null): string {
    return description ? `<div class="description">${description}</div>` : '';
  }

  private error(bindId: string): string {
    const message = this.params.errors?.[bindId];
    return message ? `<div class="error">${escapeHtml(message)}</div>` : '';
  }

  private invalid(bindId: string): string {
    return this.params.errors?.[bindId] ? ' class="invalid"' : '';
  }

  private bind(bindId: string, kind: string): string {
    return ` data-bind="${escapeHtml(bindId)}" data-kind="${kind}"`;
  }

  private string(bindId: string): string {
    const value = this.params.values[bindId];
    return typeof value === 'string' ? value : '';
  }

  private list(bindId: string): string[] {
    const value = this.params.values[bindId];
    return Array.isArray(value) ? value.map(String) : [];
  }

  private id(): string {
    return `c${this.nextId++}`;
  }
}

const FORM_STYLE = `
body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); padding: 0 20px 20px; }
h2 { font-weight: 600; }
.row { display: flex; align-items: center; gap: 6px; margin: 6px 0; flex-wrap: wrap; }
.stack { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.children { margin-left: 24px; }
.disabled { opacity: 0.5; }
input[type="text"], input[type="number"], textarea, select {
  background: var(--vscode-input-background); color: var(--vscode-input-foreground);
  border: 1px solid var(--vscode-input-border, transparent); padding: 3px 6px; font-family: inherit; font-size: inherit;
}
input[type="text"]:not([size]) { min-width: 300px; }
textarea { width: 100%; max-width: 600px; box-sizing: border-box; }
input:focus, textarea:focus, select:focus { outline: 1px solid var(--vscode-focusBorder); outline-offset: -1px; }
input[type="checkbox"]:focus { outline-offset: 2px; }
button:focus-visible { outline: 1px solid var(--vscode-focusBorder); outline-offset: 2px; }
select.selector { display: block; min-width: 300px; max-width: 600px; margin: 6px 0; padding: 0; }
select.selector option { padding: 2px 6px; }
select.selector option:checked { background: var(--vscode-list-inactiveSelectionBackground); color: var(--vscode-list-inactiveSelectionForeground, inherit); }
select.selector:focus option:checked { background: var(--vscode-list-activeSelectionBackground); color: var(--vscode-list-activeSelectionForeground); }
.invalid { border-color: var(--vscode-inputValidation-errorBorder); }
.error { color: var(--vscode-errorForeground); margin: 2px 0 6px; }
.description { color: var(--vscode-descriptionForeground); margin: 2px 0 6px; max-width: 600px; }
.description code { font-family: var(--vscode-editor-font-family); }
.block-label { display: block; margin: 8px 0 4px; }
fieldset { border: none; padding: 0; margin: 12px 0; }
legend { font-weight: 600; padding: 0; margin-bottom: 4px; }
fieldset > :not(legend) { margin-left: 12px; }
hr { border: none; border-top: 1px solid var(--vscode-panel-border); margin: 12px 0; }
.separator { display: flex; align-items: center; gap: 8px; margin: 12px 0; color: var(--vscode-descriptionForeground); }
.separator::after { content: ""; flex: 1; border-top: 1px solid var(--vscode-panel-border); }
.table-row { display: flex; gap: 4px; margin: 2px 0; }
.table-row.header span { flex: 1; font-weight: 600; }
.table-row input { flex: 1; min-width: 120px; }
.tab-headers { display: flex; gap: 2px; border-bottom: 1px solid var(--vscode-panel-border); margin: 8px 0; }
.tab { background: none; color: var(--vscode-foreground); border: none; border-bottom: 2px solid transparent; padding: 4px 10px; cursor: pointer; }
.tab.selected { border-bottom-color: var(--vscode-focusBorder); }
button.primary, button.secondary { border: none; padding: 4px 12px; cursor: pointer; font-family: inherit; font-size: inherit; }
button.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
button.primary:hover { background: var(--vscode-button-hoverBackground); }
button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
button:disabled { opacity: 0.5; cursor: default; }
.buttons { display: flex; gap: 8px; margin-top: 20px; }
`;

/**
 * The script of the webview. It enables the children of a checked checkbox, edits the rows of a table, switches the
 * tabs, and posts the values on a submit. It is plain JavaScript in a string, because the webview loads no module.
 */
const FORM_SCRIPT = `
(() => {
  const vscode = acquireVsCodeApi();
  const form = document.getElementById('form');

  const updateEnabled = () => {
    for (const children of form.querySelectorAll('.children')) {
      const parent = document.getElementById(children.dataset.parent);
      const enabled = parent.checked && !parent.disabled;
      children.classList.toggle('disabled', !enabled);
      for (const control of children.querySelectorAll('input, select, textarea, button')) control.disabled = !enabled;
    }
  };

  const tableValues = (table) => {
    const columns = JSON.parse(table.dataset.columns);
    const result = columns.map(() => []);
    for (const row of table.querySelectorAll('.table-rows .table-row')) {
      const cells = [...row.querySelectorAll('input')].map((input) => input.value);
      if (cells.every((cell) => cell.trim() === '')) continue;
      cells.forEach((cell, index) => result[index].push(cell));
    }
    return columns.map((bindId, index) => [bindId, result[index]]);
  };

  const collect = () => {
    const values = {};
    for (const control of form.querySelectorAll('[data-bind]')) {
      const bindId = control.dataset.bind;
      switch (control.dataset.kind) {
        case 'checkbox': values[bindId] = control.checked; break;
        case 'string': case 'dropdown': values[bindId] = control.value; break;
        case 'expandableString': values[bindId] = control.value.split('\\n').join(control.dataset.separator); break;
        case 'number': {
          const number = Number(control.value);
          values[bindId] = control.value.trim() !== '' && Number.isFinite(number) ? number : control.value;
          break;
        }
        case 'multiSelector':
          values[bindId] = [...control.selectedOptions].map((option) => Number(option.value));
          break;
      }
    }
    for (const table of form.querySelectorAll('[data-kind="table"]')) {
      for (const [bindId, cells] of tableValues(table)) values[bindId] = cells;
    }
    return values;
  };

  form.addEventListener('change', (event) => {
    if (event.target.type === 'checkbox') updateEnabled();
  });
  form.addEventListener('click', (event) => {
    const target = event.target;
    if (target.classList.contains('add-row')) {
      const table = target.closest('.table');
      const row = table.querySelector('template').content.firstElementChild.cloneNode(true);
      table.querySelector('.table-rows').appendChild(row);
      row.querySelector('input').focus();
    } else if (target.classList.contains('remove-row')) {
      target.closest('.table-row').remove();
    } else if (target.classList.contains('tab')) {
      const tabSet = target.closest('.tab-set');
      for (const tab of tabSet.querySelectorAll(':scope > .tab-headers > .tab')) tab.classList.toggle('selected', tab === target);
      for (const panel of tabSet.querySelectorAll(':scope > .tab-panel')) panel.hidden = panel.dataset.tabPanel !== target.dataset.tab;
    }
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    vscode.postMessage({ type: 'submit', values: collect() });
  });
  document.getElementById('cancel').addEventListener('click', () => vscode.postMessage({ type: 'cancel' }));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') vscode.postMessage({ type: 'cancel' });
  });

  updateEnabled();
  const first = form.querySelector('.invalid') ?? form.querySelector('input, select, textarea');
  first?.focus();
})();
`;
