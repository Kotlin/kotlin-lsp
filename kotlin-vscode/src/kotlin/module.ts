import { ExtensionContext } from 'vscode';
import keyHandler from './keyHandler';
import { registerHandleKeyType } from '../handleKeyType'
import {DocumentParser} from "../DocumentParser"

export default async (context: ExtensionContext) => {
    const parser = await DocumentParser.create(context, 'kotlin');
    await registerHandleKeyType(context, parser, keyHandler);
}