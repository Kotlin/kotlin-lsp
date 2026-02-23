/**
 * See language-server/docs/release.md for details.
 */

const fs = require('fs');
const path = require('path');
const intellijVscodeDir = path.resolve(__dirname, '../../../../language-server/intellij-vscode');

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

applyPatch('package.json', path.join(intellijVscodeDir, 'package-patch.json'), 'package.json');
applyPatch('package.json', path.join(intellijVscodeDir, 'package-patch-sql.json'), 'package.json');
copyOverlayDirectory(path.join(intellijVscodeDir, 'src'), path.resolve(__dirname, 'src'));
copyOverlayDirectory(path.join(intellijVscodeDir, 'bin'), path.resolve(__dirname, 'bin'));
