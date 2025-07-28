/**
 * See language-server/docs/release.md for details.
 */

const fs = require('fs');
const path = require('path');

function merge(target, patch) {
    if (Array.isArray(target) && Array.isArray(patch)) {
        return [...target, ...patch];
    }

    if (isObject(target) && isObject(patch)) {
        const result = {...target};
        for (const key of Object.keys(patch)) {
            if (key in target) {
                result[key] = merge(target[key], patch[key]);
            } else {
                result[key] = patch[key];
            }
        }
        return result;
    }

    return patch;
}

function isObject(value) {
    return value && typeof value === 'object' && !Array.isArray(value);
}

function applyPatch(targetPath, patchPath, outputPath) {
    const target = JSON.parse(fs.readFileSync(targetPath, 'utf8'));
    const patch = JSON.parse(fs.readFileSync(patchPath, 'utf8'));

    const merged = merge(target, patch);

    fs.writeFileSync(outputPath, JSON.stringify(merged, null, 2), 'utf8');
    console.log(`Merged JSON written to ${outputPath}`);
}


applyPatch('package.json', '../../intellij-vscode/package-patch.json', 'package.json');

