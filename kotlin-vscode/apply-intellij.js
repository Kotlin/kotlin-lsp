/**
 * See language-server/docs/release.md for details.
 */

const fs = require('fs');
const path = require('path');
const intellijVscodeDir = path.resolve(__dirname, '../../../../../language-server/intellij-vscode');
const bundleType = process.env.BUNDLE_TYPE || 'kotlin-server';

if (bundleType === 'kotlin-server') return;
if (!fs.existsSync(intellijVscodeDir)) return;

function merge(target, patch) {
    if (Array.isArray(target) && Array.isArray(patch)) {
        return [...target, ...patch];
    }

    if (isObject(target) && isObject(patch)) {
        const result = {...target};
        for (const key in patch) {
            result[key] = merge(target[key], patch[key]);
        }
        return result;
    }

    // merge(["a", "b", "c"], {"1": "B"}) -> ["a", "B", "c"]
    if (Array.isArray(target) && isObject(patch)) {
        const result = [...target];
        for (const key in patch) {
            result[+key] = merge(target[key], patch[key]);
        }
        return result;
    }

    return patch;
}

function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function applyPatch(targetPath, patchPath, outputPath) {
    const target = JSON.parse(fs.readFileSync(targetPath, 'utf8'));
    const patch = JSON.parse(fs.readFileSync(patchPath, 'utf8'));

    const merged = merge(target, patch);

    fs.writeFileSync(outputPath, JSON.stringify(merged, null, 2), 'utf8');
    console.log(`Merging ${patchPath} to ${outputPath}`);
}

/**
 * Recursively copies IntelliJ related files into kotlin-vscode sources.
 * Missing source directories are ignored to keep the patch step resilient.
 * Existing files with the same name are overwritten by overlay versions.
 */
function copyOverlayDirectory(sourceDir, targetDir) {
    if (!fs.existsSync(sourceDir)) {
        return;
    }

    fs.mkdirSync(targetDir, {recursive: true});
    for (const entry of fs.readdirSync(sourceDir, {withFileTypes: true})) {
        const sourcePath = path.join(sourceDir, entry.name);
        const targetPath = path.join(targetDir, entry.name);
        if (entry.isDirectory()) {
            if (fs.existsSync(targetPath) && !fs.statSync(targetPath).isDirectory()) {
                fs.rmSync(targetPath, {recursive: true, force: true});
            }
            copyOverlayDirectory(sourcePath, targetPath);
            continue;
        }
        if (entry.isFile()) {
            if (fs.existsSync(targetPath) && fs.statSync(targetPath).isDirectory()) {
                fs.rmSync(targetPath, {recursive: true, force: true});
            }
            fs.copyFileSync(sourcePath, targetPath);
            fs.chmodSync(targetPath, fs.statSync(sourcePath).mode);
        }
    }
}

function patchPackageJson(patchFile) {
    applyPatch('package.json', path.join(intellijVscodeDir, patchFile), 'package.json');
}

function copyOverlayDir(subdir) {
    copyOverlayDirectory(path.join(intellijVscodeDir, subdir), path.join(__dirname, subdir));
}

function copyFile(src, dest) {
    fs.copyFileSync(path.join(intellijVscodeDir, src), path.join(__dirname, dest));
}

require(path.join(intellijVscodeDir, 'apply-intellij-impl.js'))(bundleType, patchPackageJson, copyOverlayDir, copyFile);
