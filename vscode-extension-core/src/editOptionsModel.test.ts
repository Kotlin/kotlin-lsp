import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  type EditOptionsParams,
  parseEditOptionsMessage,
  renderEditOptionsHtml,
} from './editOptionsModel';

function params(overrides: Partial<EditOptionsParams>): EditOptionsParams {
  return {
    title: 'Edit contract',
    components: [],
    values: {},
    okLabel: 'OK',
    cancelLabel: 'Cancel',
    addLabel: 'Add',
    removeLabel: 'Remove',
    ...overrides,
  };
}

test('renders the values of the controls', () => {
  const html = renderEditOptionsHtml(
    params({
      components: [
        { kind: 'string', bindId: 'contract', prefix: 'Contract:', suffix: '' },
        { kind: 'number', bindId: 'count', prefix: 'At least', suffix: 'lines', min: 0, max: 10 },
        {
          kind: 'dropdown',
          bindId: 'mode',
          prefix: 'Mode:',
          suffix: '',
          options: [
            { key: 'A', label: 'a' },
            { key: 'B', label: 'b' },
          ],
        },
      ],
      values: { contract: '_ -> fail', count: 3, mode: 'B' },
    }),
    { nonce: 'n0' },
  );

  assert.match(
    html,
    /<label for="c0">Contract:<\/label><input type="text" id="c0" data-bind="contract" data-kind="string" value="_ -&gt; fail">/,
  );
  assert.match(
    html,
    /<label for="c1">At least<\/label><input type="number"[^>]* value="3"><span>lines<\/span>/,
  );
  assert.match(html, /<option value="B" selected>b<\/option>/);
});

test('puts the children of a checkbox into a group that the checkbox enables', () => {
  const html = renderEditOptionsHtml(
    params({
      components: [
        {
          kind: 'checkbox',
          bindId: 'impure',
          label: 'Impure',
          children: [{ kind: 'string', bindId: 'mutates', prefix: 'Mutates:', suffix: '' }],
        },
      ],
      values: { impure: true, mutates: 'this' },
    }),
    { nonce: 'n0' },
  );

  assert.match(
    html,
    /<input type="checkbox" id="c0" data-bind="impure" data-kind="checkbox" checked>/,
  );
  assert.match(
    html,
    /<div class="children" data-parent="c0">\n<div class="row"><label for="c1">Mutates:/,
  );
});

test('shows the message of a value which is not valid', () => {
  const html = renderEditOptionsHtml(
    params({
      components: [{ kind: 'string', bindId: 'contract', prefix: 'Contract:', suffix: '' }],
      values: { contract: 'bad' },
      errors: { contract: 'Wrong <value>' },
    }),
    { nonce: 'n0' },
  );

  assert.match(html, /value="bad" class="invalid">/);
  assert.match(html, /<div class="error">Wrong &lt;value&gt;<\/div>/);
});

test('escapes the text of the server and keeps the HTML of a description', () => {
  const html = renderEditOptionsHtml(
    params({
      title: '<b>Title</b>',
      components: [
        {
          kind: 'string',
          bindId: 'x',
          prefix: 'A "label"',
          suffix: '',
          description: 'Use <code>this</code>',
        },
      ],
    }),
    { nonce: 'n0' },
  );

  assert.match(html, /<h2>&lt;b&gt;Title&lt;\/b&gt;<\/h2>/);
  assert.match(html, /A &quot;label&quot;/);
  assert.match(html, /<div class="description">Use <code>this<\/code><\/div>/);
  assert.match(html, /script-src 'nonce-n0'/);
});

test('renders a row of inputs for each element of a string list', () => {
  const html = renderEditOptionsHtml(
    params({
      components: [{ kind: 'stringList', bindId: 'names', label: 'Names' }],
      values: { names: ['a', 'b'] },
    }),
    { nonce: 'n0' },
  );

  assert.match(html, /data-kind="table" data-columns="\[&quot;names&quot;\]"/);
  assert.equal(html.match(/<div class="table-row"><input type="text" value="[ab]">/g)?.length, 2);
});

test('renders a multi selector as a list box', () => {
  const html = renderEditOptionsHtml(
    params({
      components: [
        {
          kind: 'multiSelector',
          bindId: 'targets',
          elements: [{ text: 'a', secondaryText: 'pkg' }, { text: 'b' }, { text: 'c' }],
          multiple: true,
          allowsEmpty: false,
        },
      ],
      values: { targets: [0, 2] },
    }),
    { nonce: 'n0' },
  );

  assert.match(
    html,
    /<select class="selector" size="3" multiple data-bind="targets" data-kind="multiSelector">/,
  );
  assert.match(
    html,
    /<option value="0" selected>a \(pkg\)<\/option><option value="1">b<\/option><option value="2" selected>c<\/option>/,
  );
});

test('accepts only well-formed messages of the webview', () => {
  assert.deepEqual(parseEditOptionsMessage({ type: 'cancel' }), { type: 'cancel' });
  assert.deepEqual(
    parseEditOptionsMessage({ type: 'submit', values: { a: true, b: ['x'], c: {} } }),
    {
      type: 'submit',
      values: { a: true, b: ['x'] },
    },
  );
  assert.equal(parseEditOptionsMessage({ type: 'submit' }), undefined);
  assert.equal(parseEditOptionsMessage('submit'), undefined);
});
