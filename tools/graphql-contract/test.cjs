const assert = require('node:assert/strict');
const { snapshot, check } = require('./check.cjs');
const schema = 'scalar Timestamp enum Status { A B } input Input { id: ID! at: Timestamp } type Item { id: ID! state: Status! } type Query { item(input: Input!): Item }';
const operation = 'query Item($input: Input!) { item(input: $input) { id state } }';
const baseline = snapshot(schema, operation);
check(snapshot(schema + ' type Unused { value: String }', operation), baseline);
assert.throws(() => snapshot(schema.replace('id: ID! state:', 'state:'), operation));
assert.throws(() => check(snapshot(schema.replace('state: Status!', 'state: Status'), operation), baseline));
assert.throws(() => check(snapshot(schema.replace('at: Timestamp', 'at: Timestamp!'), operation), baseline));
assert.throws(() => check(snapshot(schema.replace('A B', 'A B C'), operation), baseline));
assert.throws(() => check(snapshot(schema.replace('at: Timestamp', 'at: Timestamp = "2026"'), operation), baseline));
assert.throws(() => check(snapshot(schema, operation.replace('query Item', 'query Changed')), baseline));
console.log('7 contract scenarios passed: unrelated addition allowed; removed field, output nullability, required input, enum, default and operation drift rejected.');

const withFragment = "query Item($input: Input!) { item(input: $input) { ...Details } } fragment Details on Item { id state }";
assert.throws(() => check(snapshot(schema, withFragment.replace("{ id state }", "{ alias: id state }")), snapshot(schema, withFragment)));
console.log("Fragment alias/definition drift rejected.");
